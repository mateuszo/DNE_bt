/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.BluetoothChat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * This is the main Activity that displays the current chat session.
 */
public class BluetoothChat extends Activity {
    // Debugging
    private static final String TAG = "BluetoothChat";
    private static final boolean D = true;

    // Message types sent from the BluetoothChatService Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;

    // Key names received from the BluetoothChatService Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE = 1;
    private static final int REQUEST_ENABLE_BT = 2;

    // Layout Views
    private TextView mTitle;
    private ListView mConversationView;
    private ListView mStatusView; // lista wyœwietlaj¹ca co siê dzieje
    private EditText mOutEditText;
    private EditText mDestName;
    private Button mSendButton;
    private LinearLayout mSatusLayout;

    //Time
	SimpleDateFormat simpleDateFormat = new SimpleDateFormat("HH:mm:ss");
	String currentTime;
    
    private String remoteDevice;
    HashMap<String, String> neighbors = new HashMap<String, String>();
    private ArrayAdapter<String> mNewDevicesArrayAdapter;
    
    private Queue<String[]> messageQueue = new LinkedList<String[]>(); //kolejka wiadomoœci do wys³ania
    private String[] messageToSend = new String[2]; // tablica wiadomoœci w formacie String[0] - destination; String[1] - message 
    
    // Name of the connected device
    private String mConnectedDeviceName = null;
    // Array adapter for the conversation thread
    private ArrayAdapter<String> mConversationArrayAdapter;
    // Arrat adapter for the status view
    private ArrayAdapter<String> mStatusArrayAdapter;
    // String buffer for outgoing messages
    private StringBuffer mOutStringBuffer;
    // Local Bluetooth adapter
    private BluetoothAdapter mBluetoothAdapter = null;
    // Member object for the chat services
    private BluetoothChatService mChatService = null;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(D) Log.d(TAG, "+++ ON CREATE +++");

        // Set up the window layout
        //requestWindowFeature(Window.FEATURE_CUSTOM_TITLE);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.main);
        //getWindow().setFeatureInt(Window.FEATURE_CUSTOM_TITLE, R.layout.custom_title);


        
        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        
        ensureDiscoverable();
        
        // Register for broadcasts when a device is discovered
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        this.registerReceiver(mReceiver, filter);

        // Register for broadcasts when discovery has finished
        filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        this.registerReceiver(mReceiver, filter);
        
    }

    @Override
    public void onStart() {
        super.onStart();
        if(D) Log.d(TAG, "++ ON START ++");

        // If BT is not on, request that it be enabled.
        // setupChat() will then be called during onActivityResult
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        // Otherwise, setup the chat session
        } else {
            if (mChatService == null) setupChat();
        }
    }

    @Override
    public synchronized void onResume() {
        super.onResume();
        if(D) Log.d(TAG, "+ ON RESUME +");

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
        if (mChatService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mChatService.getState() == BluetoothChatService.STATE_NONE) {
              // Start the Bluetooth chat services
              mChatService.start();
            }
        }
    }

    private void setupChat() {
        Log.d(TAG, "setupChat()");

        // Initialize the array adapter for the conversation thread
        mConversationArrayAdapter = new ArrayAdapter<String>(this, R.layout.message);
        mConversationView = (ListView) findViewById(R.id.in);
        mConversationView.setAdapter(mConversationArrayAdapter);

        // Initialize the array adapter for the status view
        mStatusArrayAdapter = new ArrayAdapter<String>(this, R.layout.message);
        mStatusView = (ListView) findViewById(R.id.status_log);
        mStatusView.setAdapter(mStatusArrayAdapter);
        
        
        // Initialize the compose field with a listener for the return key
        mOutEditText = (EditText) findViewById(R.id.edit_text_out);
        mOutEditText.setOnEditorActionListener(mWriteListener);

        // Initialize the destination name field
        mDestName = (EditText) findViewById(R.id.dest_name);
        
        // Initialize the send button with a listener that for click events
        mSendButton = (Button) findViewById(R.id.button_send);
        mSendButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                // Send a message using content of the edit text widget
                TextView view = (TextView) findViewById(R.id.edit_text_out);
                String message = view.getText().toString();
                view = (TextView) findViewById(R.id.dest_name);
                String dest = view.getText().toString();
                
                
                
                addMessageToQueue(message, dest);
                mSendButton.setEnabled(false);
            }
        });

        // Initialize the BluetoothChatService to perform bluetooth connections
        mChatService = new BluetoothChatService(this, mHandler);

        // Initialize the buffer for outgoing messages
        mOutStringBuffer = new StringBuffer("");
    }

    @Override
    public synchronized void onPause() {
        super.onPause();
        if(D) Log.d(TAG, "- ON PAUSE -");
    }

    @Override
    public void onStop() {
        super.onStop();
        if(D) Log.d(TAG, "-- ON STOP --");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Stop the Bluetooth chat services
        if (mChatService != null) mChatService.stop();
        if(D) Log.d(TAG, "--- ON DESTROY ---");
    }

    private void ensureDiscoverable() {
        if(D) Log.d(TAG, "ensure discoverable");
        if (mBluetoothAdapter.getScanMode() !=
            BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 0);
            startActivity(discoverableIntent);
        }
    }

    /**
     * Sends a message.
     * @param message  A string of text to send.
     */
    
    
    private void addMessageToQueue(String message, String destination){
    	messageToSend[0] = destination;
        messageToSend[1] = message;
        messageQueue.add(messageToSend); //dodaje wiadomoœæ do kolejki
        
        doDiscovery();
    }
    
    public void sendMessageBt(String message) {
        // Check that we're actually connected before trying anything
       if (mChatService.getState() != BluetoothChatService.STATE_CONNECTED) {
           Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show();
           return;
       }
    	

        // Check that there's actually something to send
       if(message.length() >0 ) {
    	   // Get the message bytes and tell the BluetoothChatService to write
	        byte[] send = message.getBytes();
	        mChatService.write(send);
	        
	    	currentTime = simpleDateFormat.format(new Date());
	    	updateStatus(currentTime + ">> message sent");
	    	
	        // Reset out string buffer to zero and clear the edit text field
	        mOutStringBuffer.setLength(0);
	        mOutEditText.setText(mOutStringBuffer);
	       
	        resetChatService();
       }
        
       mSendButton.setEnabled(true);
        
    }
    

    
    

    private void resetChatService() {
        mChatService.stop();
        if (mChatService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mChatService.getState() == BluetoothChatService.STATE_NONE) {
              // Start the Bluetooth chat services
              mChatService.start();
          	currentTime = simpleDateFormat.format(new Date());
          	updateStatus(currentTime + ">> ChatService reset");
          	
            }
        }		
	}

	// The action listener for the EditText widget, to listen for the return key
    private TextView.OnEditorActionListener mWriteListener =
        new TextView.OnEditorActionListener() {
        public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
            // If the action is a key-up event on the return key, send the message
            if (actionId == EditorInfo.IME_NULL && event.getAction() == KeyEvent.ACTION_UP) {
                String message = view.getText().toString();
                view = (TextView) findViewById(R.id.dest_name);
                String dest = view.getText().toString();
                addMessageToQueue(message, dest);
            }
            if(D) Log.i(TAG, "END onEditorAction");
            return true;
        }
    };

    // The Handler that gets information back from the BluetoothChatService
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_STATE_CHANGE:
                if(D) Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
                switch (msg.arg1) {
                case BluetoothChatService.STATE_CONNECTED:

                    setTitle("connected to:" + mConnectedDeviceName);
                    //jeœli po³¹czy³eœ siê z prawid³owym urz¹dzeniem to wyœlij do niego wiadomoœæ
                    if(mConnectedDeviceName.equals(messageToSend[0])){
                    	sendMessageBt(messageToSend[1]);
                    }
                    
                    break;
                case BluetoothChatService.STATE_CONNECTING:
                    //mTitle.setText(R.string.title_connecting);
                    setTitle("connecting...");
                    break;
                case BluetoothChatService.STATE_LISTEN:
                case BluetoothChatService.STATE_NONE:
                    //mTitle.setText(R.string.title_not_connected);
                    setTitle("not connected");
                    break;
                }
                break;
            case MESSAGE_WRITE:
                byte[] writeBuf = (byte[]) msg.obj;
                // construct a string from the buffer
                String writeMessage = new String(writeBuf);
                mConversationArrayAdapter.add("Me:  " + writeMessage);
                break;
            case MESSAGE_READ:
                byte[] readBuf = (byte[]) msg.obj;
                // construct a string from the valid bytes in the buffer
                String readMessage = new String(readBuf, 0, msg.arg1);
                processMessage(readMessage);
                mConversationArrayAdapter.add(mConnectedDeviceName+":  " + readMessage);
                resetChatService();
                break;
            case MESSAGE_DEVICE_NAME:
                // save the connected device's name
                mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
                Toast.makeText(getApplicationContext(), "Connected to "
                               + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                break;
            case MESSAGE_TOAST:
                Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST),
                               Toast.LENGTH_SHORT).show();
                break;
            }
        }

		private void processMessage(String readMessage) {
			
		}
    };

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(D) Log.d(TAG, "onActivityResult " + resultCode);
        switch (requestCode) {
        case REQUEST_ENABLE_BT:
            // When the request to enable Bluetooth returns
            if (resultCode == Activity.RESULT_OK) {
                // Bluetooth is now enabled, so set up a chat session
                setupChat();
            } else {
                // User did not enable Bluetooth or an error occured
                Log.d(TAG, "BT not enabled");
                Toast.makeText(this, R.string.bt_not_enabled_leaving, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
    
    public void updateStatus(String message){
    	mStatusArrayAdapter.add(message);
    	mStatusView.setSelection(mStatusView.getAdapter().getCount()-1);
    }


    
    /**
     * Start device discover with the BluetoothAdapter
     */
    private void doDiscovery() {
        if (D) Log.d(TAG, "doDiscovery()");

        // Indicate scanning in the title
        setProgressBarIndeterminateVisibility(true);
        //mTitle.setText("scanning...");
        setTitle("scanning...");
        // If we're already discovering, stop it
        if (mBluetoothAdapter.isDiscovering()) {
        	mBluetoothAdapter.cancelDiscovery();
        }

        // Request discover from BluetoothAdapter
        mBluetoothAdapter.startDiscovery();
    	currentTime = simpleDateFormat.format(new Date());
    	updateStatus(currentTime + ">> Discovery started");
    }
    
    // The BroadcastReceiver that listens for discovered devices and
    // changes the title when discovery is finished
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            // When discovery finds a device
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Get the BluetoothDevice object from the Intent
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);                	
  
               neighbors.put(device.getName(), device.getAddress());
               //jeœli ju¿ znalaz³eœ odpowiednie urz¹dzenie zakoñcz skanowanie
               if(device.getName().equals(messageToSend[0])){
            	   mBluetoothAdapter.cancelDiscovery();
               }
                	if (D) Log.d(TAG, neighbors.get(device.getName()));            
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                setProgressBarIndeterminateVisibility(false);
                setTitle("scanning finished");
            	if (D) Log.d(TAG, "discovery finished");            
            	//mTitle.setText("scanning finished");
            	
              	currentTime = simpleDateFormat.format(new Date());
              	updateStatus(currentTime + ">> Discovery finished");
              	

              	//po zakoñczeniu skanowania po³¹cz z urz¹dzeniem docelowym
            	if(neighbors.get(messageToSend[0]) != null){
            		BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(neighbors.get(messageToSend[0]));
            		// Attempt to connect to the device
            		mChatService.connect(device);
                  	currentTime = simpleDateFormat.format(new Date());
                  	updateStatus(currentTime + ">> connect to" + messageToSend[0]);
            	}
            	else {
            		Toast.makeText(getApplicationContext(), messageToSend[0] + " is unreachable", Toast.LENGTH_SHORT).show();
                  	currentTime = simpleDateFormat.format(new Date());
                  	updateStatus(currentTime + ">> " + messageToSend[0] + " unreachable, preparing RREQ");
            		//TODO check Reouting table and send RREQ if required
            	}
              	
            }
        }

		
    };

}