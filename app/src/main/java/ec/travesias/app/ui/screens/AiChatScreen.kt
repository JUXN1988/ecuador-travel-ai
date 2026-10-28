package ec.travesias.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ec.travesias.app.data.model.ChatMessage
import ec.travesias.app.data.model.MessageSender
import ec.travesias.app.ui.theme.*
import ec.travesias.app.viewmodel.TravelViewModel

@Composable
fun AiChatScreen(viewModel: TravelViewModel, onOpenRoute: () -> Unit = {}) {
    val messages by viewModel.chatMessages.collectAsState()
    val isThinking by viewModel.isAiThinking.collectAsState()
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, isThinking) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceLight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = AndeanBlue,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        "Guía IA",
                        style = MaterialTheme.typography.titleLarge,
                        color = AndeanBlue,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Turismo y cultura de Ecuador",
                        style = MaterialTheme.typography.bodySmall,
                        color = VolcanicGray
                    )
                }
            }

        // Messages List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubble(
                    msg = msg,
                    onSuggestionClick = { suggestion -> viewModel.sendChatMessage(suggestion) },
                    onOpenRoute = onOpenRoute
                )
            }

            if (isThinking) {
                item {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = AndeanBlue,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "AeroGuía está consultando información turística...",
                            style = MaterialTheme.typography.bodySmall,
                            color = VolcanicGray
                        )
                    }
                }
            }
        }

        // Bottom Input Field
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SurfaceContainerLowest,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Escribe tu duda sobre Ecuador...", color = VolcanicGray) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AndeanBlue,
                        unfocusedBorderColor = SurfaceContainerHighest
                    ),
                    maxLines = 3
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            val msg = textInput
                            textInput = ""
                            viewModel.sendChatMessage(msg)
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (textInput.isNotBlank()) BrandFill else SurfaceContainerHighest,
                            CircleShape
                        )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Enviar",
                        tint = if (textInput.isNotBlank()) Color.White else VolcanicGray
                    )
                }
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage, onSuggestionClick: (String) -> Unit, onOpenRoute: () -> Unit = {}) {
    val isUser = msg.sender == MessageSender.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) BrandFill else SurfaceContainerLowest,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    color = if (isUser) Color.White else AndeanBlue
                )

                // Lo que el agente hizo de verdad con sus herramientas
                if (msg.steps.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = VolcanicGray.copy(alpha = 0.15f))
                    Spacer(Modifier.height(8.dp))
                    msg.steps.forEach { paso ->
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = 3.dp)) {
                            Icon(
                                Icons.Default.AutoAwesome, contentDescription = null,
                                tint = EquatorialGold, modifier = Modifier.size(13.dp).padding(top = 2.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                paso,
                                style = MaterialTheme.typography.labelSmall,
                                color = VolcanicGray
                            )
                        }
                    }
                }

                if (msg.offline) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = VolcanicGray, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Respondido sin conexión, con los lugares descargados",
                            style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                    }
                }
            }
        }

        // Si el agente tocó la ruta, atajo para abrirla
        if (!isUser && msg.routeChanged) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenRoute,
                colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ver mi ruta", style = MaterialTheme.typography.labelMedium)
            }
        }

        // Suggestions pills
        if (!isUser && msg.suggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Preguntas sugeridas:",
                style = MaterialTheme.typography.labelSmall,
                color = VolcanicGray,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(msg.suggestions) { suggestion ->
                    Surface(
                        color = EquatorialGold.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable { onSuggestionClick(suggestion) }
                    ) {
                        Text(
                            text = suggestion,
                            color = AndeanBlue,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
