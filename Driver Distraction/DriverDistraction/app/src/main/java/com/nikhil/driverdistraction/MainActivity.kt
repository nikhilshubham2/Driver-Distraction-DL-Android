package com.nikhil.driverdistraction

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraX
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.nikhil.driverdistraction.ml.MyModel
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var imageView: ImageView
    private lateinit var textViewOutput: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var isCameraOpen = false
    val map = arrayOf<String>(
        "Safe driving",
        "Texting - right",
        "Talking on the phone - right",
        "Texting - left",
        "Talking on the phone - left",
        "Operating the radio",
        "Drinking",
        "Reaching behind",
        "Hair and makeup",
        "Talking to passenger"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.main_activity)

        imageView = findViewById(R.id.imageView)
        textViewOutput = findViewById(R.id.textViewOutput)
        val selectPhotoBtn: Button = findViewById(R.id.selectPhotoButton)
        val startFrontCameraBtn: Button = findViewById(R.id.startFrontCameraButton)
        val startBackCameraBtn: Button = findViewById(R.id.startBackCameraButton)

        selectPhotoBtn.setOnClickListener {
            stopCamera()

            val intent = Intent()
            intent.setAction(Intent.ACTION_GET_CONTENT)
            intent.setType("image/*")
            startActivityForResult(intent, 69)
        }

        startFrontCameraBtn.setOnClickListener {
            if (cameraProvider != null){
                stopCamera()
                startFrontCameraBtn.text = "Start Front Camera"
                startFrontCameraBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.purple_500))
            } else {
                startFrontCameraBtn.text = "Stop Front Camera"
                startFrontCameraBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.red))
                startImageCapture(CameraSelector.DEFAULT_FRONT_CAMERA)
                // back cam auto off
                startBackCameraBtn.text = "Start Back Camera"
                startBackCameraBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.purple_500))
            }
        }

        startBackCameraBtn.setOnClickListener {
            if (cameraProvider != null){
                stopCamera()
                startBackCameraBtn.text = "Start Back Camera"
                startBackCameraBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.purple_500))
            } else {
                startBackCameraBtn.text = "Stop Back Camera"
                startBackCameraBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.red))
                startImageCapture(CameraSelector.DEFAULT_BACK_CAMERA)
                // front cam auto off
                startFrontCameraBtn.text = "Start Front Camera"
                startFrontCameraBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.purple_500))
            }
        }

    }

    private fun startImageCapture(cameraSelector: CameraSelector) {
        isCameraOpen = true

        GlobalScope.launch(Dispatchers.Main) {
            // Wait for the camera to be bound before starting image capture
            cameraProvider = waitForCameraProvider()

            while (isActive) {
                if (!isCameraOpen){
                    break
                }
                // Capture image only if cameraProvider is not null
                cameraProvider?.let { provider ->
                    captureImage(provider, cameraSelector)
                }
                delay(3000) // Capture every 3 seconds
            }
        }
    }

    private fun waitForCameraProvider(): ProcessCameraProvider? {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        return try {
            cameraProviderFuture.get()
        } catch (exc: Exception) {
            Log.e("_______________", "Error getting camera provider", exc)
            null
        }
    }

    private fun captureImage(cameraProvider: ProcessCameraProvider, cameraSelector: CameraSelector) {
        val imageCapture = ImageCapture.Builder().build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                imageCapture
            )
        } catch (exc: Exception) {
            Log.e("________________", "Error binding ImageCapture to camera", exc)
            return
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            File(externalMediaDirs.first(), "${System.currentTimeMillis()}.jpg")
        ).build()

        imageCapture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                // Image saved successfully
                val savedUri = outputFileResults.savedUri // ?: Uri.fromFile(outputOptions.file!!)
                imageView.post {
                    imageView.setImageURI(savedUri)
                    if (savedUri != null) {
                        useModel(BitmapFactory.decodeFile(savedUri.path))
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                // Error occurred while saving image
                Log.e("______________", "Error capturing image: ${exception.message}", exception)
                Toast.makeText(this@MainActivity, "Error capturing image: ${exception.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun stopCamera() {
        // Unbind all camera use cases and release resources
        cameraProvider?.unbindAll()
        cameraProvider = null
        isCameraOpen = false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 69){
            val uri = data?.data
            val bitmap: Bitmap = MediaStore.Images.Media.getBitmap(this.contentResolver ,uri)
            imageView.setImageBitmap(bitmap)
            useModel(bitmap)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun useModel(imageBitmap: Bitmap){

        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(100, 100, ResizeOp.ResizeMethod.BILINEAR))
            .build()

        // byteBuffer to be RGB
        var tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(imageBitmap)

        tensorImage = imageProcessor.process(tensorImage)

        val model = MyModel.newInstance(this)

        // Creates inputs for reference.
        val inputFeature0 = TensorBuffer.createFixedSize(intArrayOf(1, 100, 100, 3), DataType.FLOAT32)
        inputFeature0.loadBuffer(tensorImage.buffer)

        // Runs model inference and gets result.
        val outputs = model.process(inputFeature0)
        val outputFeature0 = outputs.outputFeature0AsTensorBuffer
        val outputArray = outputFeature0.floatArray
        val finalOutput = outputArray.max()
        val finalOutputIndex = outputArray.indexOfFirst { it == finalOutput }

//        Log.e("__________3", ""+outputFeature0.getFloatValue(0))
//        Log.e("__________3", ""+outputFeature0.getFloatValue(1))
//        Log.e("__________3", ""+outputFeature0.getFloatValue(2))
//        Log.e("__________3", ""+outputFeature0.getFloatValue(3))
//        Log.e("__________3", ""+outputFeature0.getFloatValue(4))
//        Log.e("__________3", ""+outputFeature0.getFloatValue(5))
//        Log.e("__________3", ""+outputFeature0.getFloatValue(6))
//        Log.e("__________3", ""+outputFeature0.getFloatValue(7))
//        Log.e("__________3", ""+outputFeature0.getFloatValue(8))
//        Log.e("__________3", ""+outputFeature0.getFloatValue(9))

        textViewOutput.setText(
                    "Output: "+map[finalOutputIndex]+"\n"+
                    "Class: "+finalOutputIndex+"\n"+
                    "Accuracy Level: "+finalOutput*100+"%"
        )

        // Releases model resources if no longer used.
        model.close()
    }
}
