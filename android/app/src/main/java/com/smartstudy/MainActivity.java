package com.smartstudy;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SafFolderPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
