/*
# Copyright (c) 2020-2021 Qualcomm Innovation Center, Inc.
#
# Redistribution and use in source and binary forms, with or without
# modification, are permitted (subject to the limitations in the
# disclaimer below) provided that the following conditions are met:
#
#    * Redistributions of source code must retain the above copyright
#      notice, this list of conditions and the following disclaimer.
#
#    * Redistributions in binary form must reproduce the above
#      copyright notice, this list of conditions and the following
#      disclaimer in the documentation and/or other materials provided
#      with the distribution.
#
#    * Neither the name Qualcomm Innovation Center nor the names of its
#      contributors may be used to endorse or promote products derived
#      from this software without specific prior written permission.
#
# NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE
# GRANTED BY THIS LICENSE. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT
# HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
# WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
# MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
# IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
# ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
# DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
# GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
# INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
# IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
# OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
# IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.example.android.camera2.video.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.preference.*
import com.example.android.camera2.video.CameraActivity
import com.example.android.camera2.video.CameraActivity.Companion.mActivity
import com.example.android.camera2.video.CameraSettingsUtil.getCameraSettings
import com.example.android.camera2.video.R
import java.io.*
import java.text.DecimalFormat
import kotlin.math.abs


class CameraFragmentSettings : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        Log.i(TAG, "onCreatePreferences")
        setPreferencesFromResource(R.xml.camera_preferences, rootKey)
        val cameraManager =
                requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraList = enumerateVideoCameras(cameraManager)

        val detectedCameras = mutableListOf<String>()
        val detectedCameraIds = mutableListOf<String>()

        for (camera in cameraList) {
            detectedCameras.add("${camera.orientation} (${camera.cameraId})")
            detectedCameraIds.add(camera.cameraId)
        }

        val screen: PreferenceScreen = this.preferenceScreen
        val cameraPreference = screen.findPreference<ListPreference>("camera_id");
        cameraPreference?.entries = detectedCameras.toTypedArray()
        cameraPreference?.entryValues = detectedCameraIds.toTypedArray()

        try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            val version = pInfo.versionName
            val versionPref = screen.findPreference<EditTextPreference>("version_info")
            if (versionPref != null) {
                versionPref.summary = version.toString()
                versionPref.isEnabled = false
            }
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }

        val defogPref = screen.findPreference<Preference>("defog_file")
        defogPref?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val chooseFile = Intent(Intent.ACTION_GET_CONTENT)
            chooseFile.addCategory(Intent.CATEGORY_OPENABLE)
            chooseFile.type = "application/json"
            startActivityForResult(
                    Intent.createChooser(chooseFile, "Choose a file"),
                    PICKFILE_RESULT_CODE1
            )
            true
        }
        val exposurePref = screen.findPreference<Preference>("exposure_file")
        exposurePref?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val chooseFile = Intent(Intent.ACTION_GET_CONTENT)
            chooseFile.addCategory(Intent.CATEGORY_OPENABLE)
            chooseFile.type = "application/json"
            startActivityForResult(
                    Intent.createChooser(chooseFile, "Choose a file"),
                    PICKFILE_RESULT_CODE2
            )
            true
        }
        val anrPref = screen.findPreference<Preference>("anr_file")
        anrPref?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val chooseFile = Intent(Intent.ACTION_GET_CONTENT)
            chooseFile.addCategory(Intent.CATEGORY_OPENABLE)
            chooseFile.type = "application/json"
            startActivityForResult(
                    Intent.createChooser(chooseFile, "Choose a file"),
                    PICKFILE_RESULT_CODE3
            )
            true
        }

        val ltmPref = screen.findPreference<Preference>("ltm_file")
        ltmPref?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val chooseFile = Intent(Intent.ACTION_GET_CONTENT)
            chooseFile.addCategory(Intent.CATEGORY_OPENABLE)
            chooseFile.type = "application/json"
            startActivityForResult(
                    Intent.createChooser(chooseFile, "Choose a file"),
                    PICKFILE_RESULT_CODE4
            )
            true
        }

        updateCameraPreferences()
        updateEncodePreference()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.i(TAG, "onActivityResult")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICKFILE_RESULT_CODE1 && resultCode == Activity.RESULT_OK) {
            copyToFile(data, "/Defog_Table.json")
        } else if (requestCode == PICKFILE_RESULT_CODE2 && resultCode == Activity.RESULT_OK) {
            copyToFile(data, "/Exposure_Table.json")
        } else if (requestCode == PICKFILE_RESULT_CODE3 && resultCode == Activity.RESULT_OK) {
            copyToFile(data, "/ANR_Table.json")
        } else if (requestCode == PICKFILE_RESULT_CODE4 && resultCode == Activity.RESULT_OK) {
            copyToFile(data, "/LTM_Table.json")
        }
    }

    private fun copyToFile(data: Intent?, filename: String) {
        val contentDescriber: Uri? = data?.data
        var input: InputStream? = null
        var output: OutputStream? = null
        try {
            input = context?.contentResolver?.openInputStream(contentDescriber!!)
            output = FileOutputStream(File(context?.filesDir?.path!! + filename))
            val buffer = ByteArray(1024)
            var len: Int
            if (input != null) {
                while (input.read(buffer).also { len = it } != -1) {
                    output.write(buffer, 0, len)
                }
            }
        } finally {
            input?.close()
            output?.close()
            Toast.makeText(context, "File copy successful", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Log.i(TAG, "onViewCreated")
        super.onViewCreated(view, savedInstanceState)
        val navHeight = resources.getDimensionPixelSize(resources.getIdentifier("navigation_bar_height", "dimen", "android"))
        if (navHeight > 0) {
            listView.setPadding(0, 0, 0, navHeight)
        }
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
        preferenceScreen.sharedPreferences
                .registerOnSharedPreferenceChangeListener(this)
        //Tab switch successful
        (mActivity?.get() as CameraActivity).enableTabs()
    }

    override fun onPause() {
        Log.i(TAG, "onPause")
        super.onPause()
        preferenceScreen.sharedPreferences
                .unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        Log.i(TAG, "onSharedPreferenceChanged")
        when (key) {
            "camera_id" -> updateCameraPreferences()
            "dual_camera" -> {
                val screen: PreferenceScreen = this.preferenceScreen
                val dualCam = screen.findPreference<SwitchPreference>("dual_camera")
                val threeCam = screen.findPreference<SwitchPreference>("three_camera")

                if (threeCam != null && dualCam != null) {
                    threeCam.isChecked = when (dualCam.isChecked) {
                        true -> false
                        false -> true
                    }
                }
            }
            "three_camera" -> {
                val screen: PreferenceScreen = this.preferenceScreen
                val dualCam = screen.findPreference<SwitchPreference>("dual_camera")
                val threeCam = screen.findPreference<SwitchPreference>("three_camera")

                if (threeCam != null && dualCam != null) {
                    dualCam.isChecked = when (threeCam.isChecked) {
                        true -> false
                        false -> true
                    }
                }
            }
            else -> updateEncodePreference()
        }
    }

    private fun updateCameraPreferences() {
        Log.i(TAG, "updateCameraPreferences")
        val settings = getCameraSettings(requireContext().applicationContext)

        val cameraManager =
                requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraList = enumerateVideoCameras(cameraManager)

        val selectedCamera = cameraList.find { it.cameraId == settings.cameraId }
        val pictureSizes = mutableListOf<String>()
        val videoSizes = mutableListOf<String>()

        if (selectedCamera != null) {
            for (size in selectedCamera.pictureSizes) {
                pictureSizes.add("${size.width}x${size.height}")
            }
            for (size in selectedCamera.videoSizes) {
                videoSizes.add("${size.width}x${size.height}")
            }
        }

        val screen: PreferenceScreen = this.preferenceScreen
        val snapshotResPreference = screen.findPreference<ListPreference>("snapshot_size");
        snapshotResPreference?.entries = pictureSizes.toTypedArray()
        snapshotResPreference?.entryValues = pictureSizes.toTypedArray()

        val videoResPreference0 = screen.findPreference<ListPreference>("vid_0_size");
        videoResPreference0?.entries = videoSizes.toTypedArray()
        videoResPreference0?.entryValues = videoSizes.toTypedArray()

        val videoResPreference1 = screen.findPreference<ListPreference>("vid_1_size");
        videoResPreference1?.entries = videoSizes.toTypedArray()
        videoResPreference1?.entryValues = videoSizes.toTypedArray()

        val videoResPreference2 = screen.findPreference<ListPreference>("vid_2_size");
        videoResPreference2?.entries = videoSizes.toTypedArray()
        videoResPreference2?.entryValues = videoSizes.toTypedArray()

        val exposureValue = screen.findPreference<ListPreference>("exposure_value");
        if (exposureValue != null) {
            getExposureValue(exposureValue)
        }
    }

    private fun getExposureValue(exposureValue: ListPreference) {
        val cameraManager =
                requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val range = cameraManager.getCameraCharacteristics(getCameraSettings(requireContext().applicationContext).cameraId).get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val max: Int = range!!.upper
        val min: Int = range!!.lower
        if (min == 0 && max == 0) {
            return
        }
        val rational = cameraManager.getCameraCharacteristics(getCameraSettings(requireContext().applicationContext).cameraId).get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val step = rational?.toDouble()
        var increment = 1
        while ((max - min) / increment > 10) {
            increment++
        }
        var start = min
        if (start < 0) {
            while (abs(start) % increment != 0) {
                start++
            }
        }
        var size = 0
        run {
            var i = start
            while (i <= max) {
                size++
                i += increment
            }
        }
        val entries = arrayOfNulls<CharSequence>(size)
        val entryValues = arrayOfNulls<CharSequence>(size)
        var count = 0
        run {
            var i = start
            while (i <= max) {
                entryValues[count] = i.toString()
                val builder = StringBuilder()
                if (i > 0) builder.append('+')
                val format = DecimalFormat("#.##")
                entries[count] = builder.append(format.format(i * step!!)).toString()
                i += increment
                count++
            }
        }
        exposureValue.entries = entries
        exposureValue.entryValues = entryValues
    }

    private fun updateEncodePreference() {
        val encoderKeys = listOf(
                "vid_0_i_min_qp_range",
                "vid_0_i_max_qp_range",
                "vid_0_b_min_qp_range",
                "vid_0_b_max_qp_range",
                "vid_0_p_min_qp_range",
                "vid_0_p_max_qp_range",
                "vid_0_i_init_qp",
                "vid_0_b_init_qp",
                "vid_0_p_init_qp",
                "vid_1_i_min_qp_range",
                "vid_1_i_max_qp_range",
                "vid_1_b_min_qp_range",
                "vid_1_b_max_qp_range",
                "vid_1_p_min_qp_range",
                "vid_1_p_max_qp_range",
                "vid_1_i_init_qp",
                "vid_1_b_init_qp",
                "vid_1_p_init_qp",
                "vid_2_i_min_qp_range",
                "vid_2_i_max_qp_range",
                "vid_2_b_min_qp_range",
                "vid_2_b_max_qp_range",
                "vid_2_p_min_qp_range",
                "vid_2_p_max_qp_range",
                "vid_2_i_init_qp",
                "vid_2_b_init_qp",
                "vid_2_p_init_qp",
        )
        val screen: PreferenceScreen = this.preferenceScreen
        for (key in encoderKeys) {
            val item = screen.findPreference<EditTextPreference>(key)
            item?.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_NUMBER
            }
            if (item != null) {
                if (item.text == "") {
                    item.text = "0"
                }
                item.summary = item.text
            }
        }
    }

    companion object {
        private val TAG = CameraFragmentSettings::class.java.simpleName
        const val PICKFILE_RESULT_CODE1 = 1
        const val PICKFILE_RESULT_CODE2 = 2
        const val PICKFILE_RESULT_CODE3 = 3
        const val PICKFILE_RESULT_CODE4 = 4

        private data class CameraInfo(
                val orientation: String,
                val cameraId: String,
                val videoSizes: Array<Size>,
                val pictureSizes: Array<Size>,
        )

        /** Converts a lens orientation enum into a human-readable string */
        private fun lensOrientationString(value: Int) = when (value) {
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }

        /** Lists all video-capable cameras and supported resolution and FPS combinations */
        @SuppressLint("InlinedApi")
        private fun enumerateVideoCameras(cameraManager: CameraManager): List<CameraInfo> {
            val availableCameras: MutableList<CameraInfo> = mutableListOf()

            cameraManager.cameraIdList.forEach { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val cameraConfig = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                val orientation = lensOrientationString(
                        characteristics.get(CameraCharacteristics.LENS_FACING)!!)
                val videoSizes = cameraConfig.getOutputSizes(MediaRecorder::class.java)
                val pictureSizes = cameraConfig.getOutputSizes(ImageFormat.JPEG)

                availableCameras.add(CameraInfo(orientation, id, videoSizes, pictureSizes))
            }
            return availableCameras
        }
    }
}
