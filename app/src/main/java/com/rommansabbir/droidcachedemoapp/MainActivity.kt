package com.rommansabbir.droidcachedemoapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rommansabbir.droidcache.DroidCache
import com.rommansabbir.droidcachedemoapp.ui.theme.DroidCacheDemoAppTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : ComponentActivity() {

    private val droidCache by lazy { DroidCache }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DroidCacheDemoAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CacheScreen(
                        modifier = Modifier.padding(innerPadding),
                        onCache = { key, value ->
                            droidCache.putString(key, value)
                        },
                        onRetrieve = { key ->
                            droidCache.getString(key)
                        },
                        onClear = {
                            droidCache.clear()
                        }
                    )
                }
            }
        }
    }
}


@Composable
fun CacheScreen(
    modifier: Modifier = Modifier,
    onCache: suspend (String, String) -> Unit,
    onRetrieve: suspend (String) -> String?,
    onClear: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()

    var cacheKey by remember { mutableStateOf("") }
    var cacheValue by remember { mutableStateOf("") }
    var retrieveKey by remember { mutableStateOf("") }
    var retrievedValue by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Cache Data")

        OutlinedTextField(
            value = cacheKey,
            onValueChange = { cacheKey = it },
            label = { Text("Key") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = cacheValue,
            onValueChange = { cacheValue = it },
            label = { Text("Value") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                scope.launch {
                    onCache(cacheKey, cacheValue)
                    cacheKey = ""
                    cacheValue = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cache Data")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(text = "Test Cache Data")

        OutlinedTextField(
            value = retrieveKey,
            onValueChange = { retrieveKey = it },
            label = { Text("Key to Retrieve") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        retrievedValue = onRetrieve(retrieveKey)
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Retrieve Data")
            }

            Button(
                onClick = {
                    scope.launch {
                        onClear()
                        retrievedValue = null
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear Cache")
            }
        }

        retrievedValue?.let {
            Text(
                text = "Retrieved Value: $it",
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}


@Preview(showBackground = true)
@Composable
fun CacheScreenPreview() {
    DroidCacheDemoAppTheme {
        CacheScreen(
            onCache = { _, _ -> },
            onRetrieve = { "Sample Value" },
            onClear = {}
        )
    }
}
