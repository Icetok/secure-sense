package me.istok.securesense.fragment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import me.istok.securesense.R;
import me.istok.securesense.data.LogBuffer;

/**
 * Fragment that displays real-time log messages coming from the monitoring service.
 * It listens for local broadcasts with log entries and updates a scrollable UI.
 */
public class MonitorFragment extends Fragment {

    private RecyclerView recycler;         // RecyclerView to display log lines
    private LogAdapter adapter;            // Adapter for RecyclerView
    private final List<String> lines = new ArrayList<>();  // List of log lines

    /**
     * BroadcastReceiver that listens for "ACCESS_LOG_EVENT" messages.
     * These are triggered by the background monitoring service.
     */
    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            final String msg = i.getStringExtra("log_message");
            if (msg == null) return;

            // Ensure updates happen on the main (UI) thread
            requireActivity().runOnUiThread(() -> {
                lines.add(msg);
                int pos = lines.size() - 1;
                adapter.notifyItemInserted(pos);
                recycler.scrollToPosition(pos);
            });
        }
    };

    /**
     * Inflate the view for this fragment and set up the RecyclerView.
     * Adds a placeholder line so the user sees something immediately.
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf,
                             @Nullable ViewGroup parent,
                             @Nullable Bundle state) {

        View root = inf.inflate(R.layout.fragment_monitor, parent, false);

        recycler = root.findViewById(R.id.logRecycler);
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));

        adapter  = new LogAdapter();
        recycler.setAdapter(adapter);

        // Show initial message in the log
        lines.add("=== SecureSense monitor ready ===");
        adapter.notifyItemInserted(0);

        return root;
    }

    /**
     * Called when the fragment becomes visible. This method:
     * - Clears and refills the log from the LogBuffer
     * - Scrolls to the latest message
     * - Registers the BroadcastReceiver
     */
    @Override
    public void onStart() {
        super.onStart();

        // Reload logs from buffer
        lines.clear();
        lines.addAll(LogBuffer.snapshot());
        adapter.notifyDataSetChanged();

        if (!lines.isEmpty()) {
            recycler.scrollToPosition(lines.size() - 1);
        }

        // Start listening for new log messages
        LocalBroadcastManager.getInstance(requireContext())
                .registerReceiver(logReceiver, new IntentFilter("ACCESS_LOG_EVENT"));
    }

    /**
     * Called when the fragment is no longer visible.
     * Unregisters the BroadcastReceiver to stop receiving logs.
     */
    @Override
    public void onStop() {
        super.onStop();
        LocalBroadcastManager.getInstance(requireContext())
                .unregisterReceiver(logReceiver);
    }

    /**
     * Adapter that handles rendering of log lines into TextViews inside the RecyclerView.
     */
    private class LogAdapter extends RecyclerView.Adapter<LogAdapter.VH> {

        /**
         * ViewHolder class that holds a single TextView for each log line.
         */
        class VH extends RecyclerView.ViewHolder {
            final TextView tv;
            VH(@NonNull View v) { super(v); tv = (TextView) v; }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int v) {
            // Inflate a single-line TextView layout
            View row = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_log_line, p, false);

            // Customize TextView appearance for terminal-style display
            TextView tv = (TextView) row;
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);  // allow text selection

            return new VH(row);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            h.tv.setText(lines.get(pos));
        }

        @Override
        public int getItemCount() {
            return lines.size();
        }
    }
}