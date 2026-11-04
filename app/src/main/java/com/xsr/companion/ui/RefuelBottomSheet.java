package com.xsr.companion.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.xsr.companion.R;
import com.xsr.companion.maintenance.FuelManager;
import com.xsr.companion.maintenance.MaintenanceManager;
import com.xsr.companion.service.XsrBleService;
import com.xsr.companion.settings.SettingsManager;
import com.xsr.companion.settings.Units;

import java.util.Locale;

/**
 * Records a fill-up. A full fill resets the tank estimate to capacity and is
 * the anchor for real tank-to-tank mileage; a partial fill just tops up.
 */
public class RefuelBottomSheet extends BottomSheetDialogFragment {

    private FuelManager fuelManager;
    private SettingsManager settings;
    private MaterialButtonToggleGroup typeGroup;
    private EditText inputLitres, inputPrice, inputNotes;
    private TextView tvOdo;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_refuel, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        fuelManager = new FuelManager(requireContext());
        settings = SettingsManager.get(requireContext());

        tvOdo = view.findViewById(R.id.tvRefuelOdo);
        typeGroup = view.findViewById(R.id.fuelTypeGroup);
        inputLitres = view.findViewById(R.id.inputLitres);
        inputPrice = view.findViewById(R.id.inputPrice);
        inputNotes = view.findViewById(R.id.inputNotes);

        double odo = Math.max(XsrBleService.sLatestBikeOdo,
                new MaintenanceManager(requireContext()).getCurrentOdometer());
        tvOdo.setText("Odometer " + Units.distance(settings, odo));

        typeGroup.check(R.id.btnFull);
        typeGroup.check(R.id.btnFull);
        typeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean full = checkedId == R.id.btnFull;
            inputLitres.setEnabled(!full);
            inputLitres.setAlpha(full ? 0.45f : 1f);
            if (full) inputLitres.setText("");
        });
        inputLitres.setEnabled(false);
        inputLitres.setAlpha(0.45f);

        MaterialButton cancel = view.findViewById(R.id.btnRefuelCancel);
        MaterialButton save = view.findViewById(R.id.btnRefuelSave);
        cancel.setOnClickListener(v -> dismiss());

        save.setOnClickListener(v -> save(odo));
    }

    private void save(double odometerKm) {
        boolean isFull = typeGroup.getCheckedButtonId() != R.id.btnPartial;

        double litres = parseDouble(inputLitres.getText().toString());
        if (!isFull && litres <= 0) {
            inputLitres.setError("Enter the litres added");
            return;
        }
        double price = parseDouble(inputPrice.getText().toString());
        String notes = inputNotes.getText().toString().trim();

        fuelManager.logRefuel(litres, price, isFull, odometerKm,
                notes.isEmpty() ? null : notes);

        // Mirror the new estimate immediately; the service refreshes it on the
        // next real telemetry tick, so no extra IPC is needed here.
        XsrBleService.sFuelRemainingL = fuelManager.getRemainingLitres();
        XsrBleService.sEstimatedRangeKm = fuelManager.getEstimatedRangeKm(
                fuelManager.getBestMileageKmL(XsrBleService.sTripAvgMileage));

        String summary = isFull
                ? String.format(Locale.getDefault(), "Full tank — %s",
                Units.fuel(settings, fuelManager.getRemainingLitres()))
                : String.format(Locale.getDefault(), "Added %s", Units.fuel(settings, litres));
        Toast.makeText(requireContext(), summary, Toast.LENGTH_SHORT).show();
        dismiss();
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return 0.0;
        }
    }
}
