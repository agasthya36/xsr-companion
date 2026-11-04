package com.xsr.companion.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.xsr.companion.R;
import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.settings.SettingsManager;
import com.xsr.companion.settings.Units;

import java.util.ArrayList;
import java.util.List;

public class RideAdapter extends RecyclerView.Adapter<RideAdapter.RideViewHolder> {

    public interface Listener {
        void onRideClick(RideDatabaseHelper.RideSummary ride);
    }

    private final List<RideDatabaseHelper.RideSummary> rides = new ArrayList<>();
    private final SettingsManager settings;
    private final Listener listener;

    public RideAdapter(SettingsManager settings, Listener listener) {
        this.settings = settings;
        this.listener = listener;
    }

    public void submit(List<RideDatabaseHelper.RideSummary> newRides) {
        rides.clear();
        if (newRides != null) rides.addAll(newRides);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RideViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_ride, parent, false);
        return new RideViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RideViewHolder holder, int position) {
        RideDatabaseHelper.RideSummary ride = rides.get(position);
        holder.date.setText(ride.getFormattedDate());
        holder.distance.setText(Units.distance(settings, ride.distanceKm));
        holder.duration.setText(ride.getFormattedMovingTime());
        holder.top.setText("top " + Units.speed(settings, ride.topSpeedKmh));
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onRideClick(ride);
        });
    }

    @Override
    public int getItemCount() {
        return rides.size();
    }

    static class RideViewHolder extends RecyclerView.ViewHolder {
        final TextView date;
        final TextView distance;
        final TextView duration;
        final TextView top;

        RideViewHolder(@NonNull View itemView) {
            super(itemView);
            date = itemView.findViewById(R.id.tvRideDate);
            distance = itemView.findViewById(R.id.tvRideDistance);
            duration = itemView.findViewById(R.id.tvRideDuration);
            top = itemView.findViewById(R.id.tvRideTop);
        }
    }
}
