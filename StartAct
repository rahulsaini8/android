package com.example.onlypodcastserviceexecutor;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.Toast;

public class StartAct extends Activity {

  private Intent service;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_start);

		service = new Intent(this, ServiceExecutor.class);
		startService(service);

	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		stopService(service);
	}

}
