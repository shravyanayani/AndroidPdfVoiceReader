package com.my.pdf_read_aloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.my.pdf_read_aloud.ui.theme.Pdf_read_aloudTheme
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var pdfReaderService: PdfReaderService
    private lateinit var preferenceRepository: PreferenceRepository
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        pdfReaderService = PdfReaderService(this)
        preferenceRepository = PreferenceRepository(this)
        
        lifecycleScope.launch {
            preferenceRepository.excludedTexts.collect { excludedTexts ->
                pdfReaderService.setExcludedTexts(excludedTexts)
            }
        }
        
        setContent {
            Pdf_read_aloudTheme {
                MainScreen(
                    pdfReaderService = pdfReaderService,
                    preferenceRepository = preferenceRepository
                )
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        pdfReaderService.shutdown()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    pdfReaderService: PdfReaderService,
    preferenceRepository: PreferenceRepository
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    
    var selectedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var selectedPdfName by remember { mutableStateOf<String?>(null) }
    var pageNumber by remember { mutableStateOf("1") }
    var excludeText by remember { mutableStateOf("") }
    var isSpeedMenuExpanded by remember { mutableStateOf(false) }
    var selectedSpeed by remember { mutableStateOf(1.0f) }
    var showDonationDialog by remember { mutableStateOf(false) }
    
    val excludedTexts by preferenceRepository.excludedTexts.collectAsState(initial = emptyList())
    val readerState by pdfReaderService.state.collectAsState()
    
    // Update pageNumber when the reader state changes
    LaunchedEffect(readerState) {
        when (readerState) {
            is PdfReaderService.ReaderState.Reading -> {
                pageNumber = (readerState as PdfReaderService.ReaderState.Reading).currentPage.toString()
            }
            is PdfReaderService.ReaderState.Paused -> {
                pageNumber = (readerState as PdfReaderService.ReaderState.Paused).currentPage.toString()
            }
            is PdfReaderService.ReaderState.Loaded -> {
                pageNumber = (readerState as PdfReaderService.ReaderState.Loaded).currentPage.toString()
            }
            else -> {}
        }
    }
    
    val speedOptions = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Persist permission
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            selectedPdfUri = uri
            
            // Get file name
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        selectedPdfName = cursor.getString(displayNameIndex)
                    }
                }
            }
            
            // Reset page number to 1 when a new PDF is selected
            pageNumber = "1"
            
            // Open the PDF file
            pdfReaderService.openPdfFile(uri)
        }
    }
    
    // Check if we should show the donation dialog
    LaunchedEffect(Unit) {
        val shouldShow = preferenceRepository.shouldShowDonationDialog.firstOrNull() ?: false
        if (shouldShow) {
            showDonationDialog = true
        }
    }
    
    if (showDonationDialog) {
        DonationDialog(
            onDismiss = {
                showDonationDialog = false
                coroutineScope.launch {
                    preferenceRepository.updateDonationDialogShownTime()
                }
            },
            onDonate = {
                showDonationDialog = false
                coroutineScope.launch {
                    preferenceRepository.updateDonationDialogShownTime()
                    // Launch PayPal intent
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://www.paypal.com")
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    } else {
                        Toast.makeText(context, "No app found to handle PayPal link", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF Voice Reader") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // File Selection Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "PDF File Selection",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Button(
                        onClick = {
                            pdfPickerLauncher.launch(arrayOf("application/pdf"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = "Select PDF File"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select PDF File")
                    }
                    
                    selectedPdfName?.let {
                        Text(
                            text = "Selected file: $it",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    //=================== Read Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Read PDF Button
                        Button(
                            onClick = {
                                val page = pageNumber.toIntOrNull() ?: 1
                                pdfReaderService.goToPage(page)
                            },
                            enabled = selectedPdfUri != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Read PDF")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Pause/Resume Button
                        Button(
                            onClick = {
                                when (readerState) {
                                    is PdfReaderService.ReaderState.Reading -> pdfReaderService.pauseReading()
                                    is PdfReaderService.ReaderState.Paused -> pdfReaderService.resumeReading()
                                    else -> {}
                                }
                            },
                            enabled = readerState is PdfReaderService.ReaderState.Reading || readerState is PdfReaderService.ReaderState.Paused,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (readerState is PdfReaderService.ReaderState.Reading) {
                                Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = "Pause"
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Pause")
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Resume"
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Resume")
                            }
                        }
                    }

                    //==================== End Read Buttons
                }
            }
            
            // Page Controls Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Page Controls",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                        //horizontalArrangement = Arrangement.SpaceEvenly
                        //,
                    ) {
                        OutlinedTextField(
                            value = pageNumber,
                            onValueChange = { value ->
                                if (value.isEmpty() || value.all { it.isDigit() }) {
                                    pageNumber = value
                                }
                            },
                            label = { Text("Page #") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { keyboardController?.hide() }
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        
                        //Spacer(modifier = Modifier.width(8.dp))


                        when (readerState) {


                            is PdfReaderService.ReaderState.Loaded,
                            is PdfReaderService.ReaderState.Paused -> {
                                Text(
                                    text = "of ${(readerState as? PdfReaderService.ReaderState.Loaded)?.totalPages
                                        ?: (readerState as? PdfReaderService.ReaderState.Paused)?.totalPages
                                        ?: (readerState as? PdfReaderService.ReaderState.Reading)?.totalPages
                                        ?: 0}",
                                    modifier = Modifier
                                        .padding(horizontal = 8.dp)
                                        .align(Alignment.CenterVertically)
                                )
                            }
                            else -> {
                                Text(
                                    text = "of ${(readerState as? PdfReaderService.ReaderState.Loaded)?.totalPages
                                        ?: (readerState as? PdfReaderService.ReaderState.Paused)?.totalPages
                                        ?: (readerState as? PdfReaderService.ReaderState.Reading)?.totalPages
                                        ?: 0}",
                                    modifier = Modifier
                                        .padding(horizontal = 8.dp)
                                        .align(Alignment.CenterVertically)
                                )


//                                Text(
//                                    text = "  ",
//                                    modifier = Modifier
//                                        .padding(horizontal = 8.dp)
//                                        .align(Alignment.CenterVertically)
//                                )
                            }


                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Previous Page Button
                        val isPreviousEnabled = when (readerState) {
                            is PdfReaderService.ReaderState.Loaded -> (readerState as PdfReaderService.ReaderState.Loaded).currentPage > 1
                            is PdfReaderService.ReaderState.Reading -> (readerState as PdfReaderService.ReaderState.Reading).currentPage > 1
                            is PdfReaderService.ReaderState.Paused -> (readerState as PdfReaderService.ReaderState.Paused).currentPage > 1
                            else -> false
                        }

                        Button(
                            onClick = { pdfReaderService.previousPage() },
                            enabled = isPreviousEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
//                            Icon(
//                                imageVector = Icons.Default.ArrowBack,
//                                contentDescription = "Prev Page"
//                            )
                            //Spacer(modifier = Modifier.width(4.dp))
                            Text("Prev Page")
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Next Page Button
                        val isNextEnabled = when (readerState) {
                            is PdfReaderService.ReaderState.Loaded -> {
                                (readerState as PdfReaderService.ReaderState.Loaded).currentPage < (readerState as PdfReaderService.ReaderState.Loaded).totalPages
                            }
                            is PdfReaderService.ReaderState.Reading -> {
                                (readerState as PdfReaderService.ReaderState.Reading).currentPage < (readerState as PdfReaderService.ReaderState.Reading).totalPages
                            }
                            is PdfReaderService.ReaderState.Paused -> {
                                (readerState as PdfReaderService.ReaderState.Paused).currentPage < (readerState as PdfReaderService.ReaderState.Paused).totalPages
                            }
                            else -> false
                        }

                        Button(
                            onClick = { pdfReaderService.nextPage() },
                            enabled = isNextEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Next Page")
                            //Spacer(modifier = Modifier.width(4.dp))
//                            Icon(
//                                imageVector = Icons.Default.ArrowForward,
//                                contentDescription = "Next Page"
//                            )
                        }


                    }
                    
//                    Row(
//                        modifier = Modifier.fillMaxWidth(),
//                        horizontalArrangement = Arrangement.SpaceEvenly
//                    ) {
//
//                    }
                }
            }
            
            // Playback Controls Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Playback Controls",
                        style = MaterialTheme.typography.titleMedium
                    )



//                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {

                        OutlinedButton(
                            onClick = { isSpeedMenuExpanded = true }
//                            ,
//                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Speed: ${selectedSpeed}x")
                        }

                        DropdownMenu(
                            expanded = isSpeedMenuExpanded,
                            onDismissRequest = { isSpeedMenuExpanded = false },
                            modifier = Modifier.wrapContentSize()
                        ) {
                            speedOptions.forEach { speed ->
                                DropdownMenuItem(
                                    onClick = {
                                        selectedSpeed = speed
                                        pdfReaderService.setReadingSpeed(speed)
                                        isSpeedMenuExpanded = false
                                    },
                                    text = { Text("${speed}x") }
                                )
                            }
                        }


                        Spacer(modifier = Modifier.width(32.dp))


                        var continuousReading by remember { mutableStateOf(true) }
                        Text(
                            text = "Auto continue    to next page",
                            modifier = Modifier.weight(1f)
                        )

                        Switch(
                            checked = continuousReading,
                            onCheckedChange = { enabled ->
                                continuousReading = enabled
                                pdfReaderService.setContinuousReading(enabled)
                            }
                        )



                    }

                    // Reading Speed Selection

//                    Box(
//                        modifier = Modifier.fillMaxWidth()
//                    ) {
//
//                    }
//
//                    Spacer(modifier = Modifier.height(8.dp))
//
//                    // Continuous Reading Switch
//                    Row(
//                        modifier = Modifier.fillMaxWidth(),
//                        verticalAlignment = Alignment.CenterVertically
//                    ) {
//
//                    }

                }
            }
            
            // Text Exclusion Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Exclude Text",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = excludeText,
                            onValueChange = { excludeText = it },
                            label = { Text("Text to exclude") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (excludeText.isNotBlank()) {
                                        coroutineScope.launch {
                                            preferenceRepository.addExcludedText(excludeText)
                                            excludeText = ""
                                        }
                                    }
                                    keyboardController?.hide()
                                }
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        IconButton(
                            onClick = {
                                if (excludeText.isNotBlank()) {
                                    coroutineScope.launch {
                                        preferenceRepository.addExcludedText(excludeText)
                                        excludeText = ""
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add excluded text"
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (excludedTexts.isNotEmpty()) {
                        Divider()
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "Excluded Texts:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            excludedTexts.forEach { text ->
                                ExcludedTextChip(
                                    text = text,
                                    onRemove = {
                                        coroutineScope.launch {
                                            preferenceRepository.removeExcludedText(text)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            // Status Section
            when (readerState) {
                is PdfReaderService.ReaderState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Error: ${(readerState as PdfReaderService.ReaderState.Error).message}",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                        )
                    }
                }
                is PdfReaderService.ReaderState.Reading -> {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Reading page ${(readerState as PdfReaderService.ReaderState.Reading).currentPage} of ${(readerState as PdfReaderService.ReaderState.Reading).totalPages}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                        )
                    }
                }
                else -> {
                    // No special status to display
                }
            }
        }
    }
    
    // Effect to handle component lifecycle
    DisposableEffect(Unit) {
        onDispose {
            pdfReaderService.shutdown()
        }
    }
}

@Composable
fun ExcludedTextChip(
    text: String,
    onRemove: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
//        Row(
//            verticalAlignment = Alignment.CenterVertically,
//            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
//        ) {

//    Column(
//        modifier = Modifier,
//        horizontalAlignment = Alignment.Start
//    ){

            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            
            IconButton(
                onClick = onRemove,
                //modifier = Modifier.size(24.dp)
                //modifier = Modifier.fillMaxSize()
                //modifier = Modifier.fillMaxSize(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
//    }

//        }
    }


}

@Composable
fun DonationDialog(
    onDismiss: () -> Unit,
    onDonate: () -> Unit
) {
    val context = LocalContext.current // Get the current context
    var openBrowser by remember { mutableStateOf(false) }

    if (openBrowser) {
        LaunchPayPalDonation(context)
        openBrowser = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Please donate to support this ad-free app") },
        text = {
            Text(
                "Thank you for using our ad-free app! We are committed to providing a completely ad-free experience. To maintain and improve the quality of our services, we rely on the support of users like you. If you find this app valuable, please consider making a voluntary contribution. \n\nYour donation, regardless of size, helps us continue development and ensures the app remains free for everyone. \n\nWe request your donation once every 30 days, and we will never charge you or show ads.",
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            Button(onClick = {
                openBrowser = true
            }) {
                Text("Donate via PayPal")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Maybe Later")
            }
        }
    )
}

@Composable
fun LaunchPayPalDonation(context: Context) {
    // Replace with your actual PayPal donation URL
    val donationUrl = "https://www.paypal.com/ncp/payment/YNY9YC96R8LFJ"

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(donationUrl))

    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e("DonationDialog", "Error launching browser: ${e.message}")
    }
}