package com.example.androidudpclient;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.net.InetAddress;
import java.util.ArrayList;

public class GetCliBeatActivity extends ListActivity {

    Button backBtn;
    Button editPatientDataBtn;
    Button addNewPatientBtn;
    private String[] patientInputString;

    final static String PATIENT_ID_STRING = "PATIENT_ID"; // used to idenfity intent-data

    @Override
    protected void onResume() {
        super.onResume();
        this.onCreate(null); // force activity to reload
    }

    /** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_getclibeat);

        // NOTE: just for testing
        if (MainActivity.patients == null) {
            MainActivity.patients = new ArrayList<Patient>(); // TODO - store this data elsewhere

            // NOTE: two fake patients to test functionality
            MainActivity.patients.add(new Patient("10.170.20.10","Test Patient 1"));
            MainActivity.patients.add(new Patient("10.170.21.9", "Test Patient 2"));
            MainActivity.patients.add(new Patient("10.170.4.188", "My Computer"));
        }

        PatientAdapter adapter = new PatientAdapter(this);
        setListAdapter(adapter);

        /** Returns to MainActivity **/
        backBtn = (Button) findViewById(R.id.getCliBeatBackBtn);
        backBtn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v) {
                finish();
            }
        });

        final Context c = this; // TODO - can this work around be avoided?

        addNewPatientBtn = (Button) findViewById(R.id.addNewPatientBtn);
        addNewPatientBtn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v) {

                final EditText patientInput = new EditText(c);
                final AlertDialog.Builder builder = new AlertDialog.Builder(c);
                builder.setTitle("Input Format: 'IP,Name'");
                builder.setView(patientInput);

                builder.setPositiveButton("Done", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            patientInputString = patientInput.getText().toString().split(",");
                        } catch (Exception e) {
                            // TODO - handle
                        }

                        boolean validIP = false;
                        try {
                            // tests validity of IP input
                            patientInputString[0] = patientInputString[0].trim();
                            patientInputString[1] = patientInputString[1].trim();

                            InetAddress.getByName(patientInputString[0]);
                            validIP = true;
                        } catch (Exception e) {
                            validIP = false;
                        }

                        // NOTE: name-length contraints were chosen somewhat arbitrarily
                        if (validIP && patientInputString[1].length() >= 3
                                && patientInputString[1].length() <= 10) {
                            MainActivity.patients.add(new Patient(patientInputString[0], patientInputString[1]));
                        } else {
                            Toast toast = Toast.makeText(c,
                                    "Invalid IP or name length (3-10 characters).", Toast.LENGTH_LONG);
                            toast.show();
                        }
                    }
                });
                builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                });

                builder.show();
            }
        });
    }

    private class PatientAdapter extends ArrayAdapter<Patient> {

        Activity activity = null;

        public PatientAdapter(ListActivity li)
        {
            super(li, 0, MainActivity.patients);
            activity = (Activity)li;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent)
        {
            if (convertView == null) {
                convertView = activity.getLayoutInflater()
                        .inflate(R.layout.list_item_patient, null);
            }

            final Patient p = MainActivity.patients.get(position);
            final Context c = this.activity;

            Button patientButton = (Button)convertView.findViewById(R.id.list_patientButton);
            patientButton.setText("IP: "  + p.getIP() + "\nName: "+ p.getName());
            patientButton.setOnClickListener(new View.OnClickListener() {

                @Override
                public void onClick(View v) {

                    final AlertDialog.Builder builder = new AlertDialog.Builder(c);
                    builder.setTitle("Go to patient page?");// Nearest selected interval will be returned.");

                    builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                            Intent intent = new Intent(c, PatientDataActivity.class);

                            // TODO - create/define/pass valid patient ID

                            intent.putExtra(PATIENT_ID_STRING, p.getIP());
                            startActivity(intent);
                        }
                    });
                    builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.cancel();
                        }
                    });

                    builder.show();
                }
            });
            return convertView;
        }
    }
}