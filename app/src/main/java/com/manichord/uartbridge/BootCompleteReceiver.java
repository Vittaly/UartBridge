package com.manichord.uartbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
//import android.widget.Toast;
import timber.log.Timber;


public class BootCompleteReceiver extends BroadcastReceiver {
    public BootCompleteReceiver() {
    }
    
    public void onReceive(Context context, Intent intent) {
      if (intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
      /*  Toast toast = Toast.makeText(context.getApplicationContext(),
                context.getResources().getString(R.string.your_message), Toast.LENGTH_LONG);
        toast.show();*/
        Timber.d("message ACTION_BOOT_COMPLETED received.");
        Intent i = new Intent(context, UsbService.class);
        
        context.startService(i); // Start UsbService
        Timber.d("service started.");
        
      }
    }
  }