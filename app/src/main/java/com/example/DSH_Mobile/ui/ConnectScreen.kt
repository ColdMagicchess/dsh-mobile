package com.example.DSH_Mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.DSH_Mobile.vm.AppViewModel
import com.example.DSH_Mobile.vm.AppUiState

@Composable
fun ConnectScreen(state: AppUiState, vm: AppViewModel) {
    var host by rememberSaveable { mutableStateOf(state.host) }
    var tokenUrl by rememberSaveable { mutableStateOf("") }
    var manualCookie by rememberSaveable { mutableStateOf("") }
    var modeIndex by rememberSaveable { mutableStateOf(0) }
    var trust by rememberSaveable { mutableStateOf(false) }

    // Boot fills the saved host asynchronously; back-fill the field once known.
    LaunchedEffect(state.host) {
        if (host.isBlank() && state.host.isNotBlank()) host = state.host
    }

    // 本页固定浅色 Flat 主题，不跟随系统深色模式。
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Flat.Accent,
            background = Flat.PageBg,
            surface = Flat.PageBg,
            onSurface = Flat.Ink,
            error = Flat.Danger,
        ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Flat.PageBg)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp, vertical = 40.dp),
        ) {
            Spacer(Modifier.height(24.dp))

            Text(
                "DeepSeek Harness",
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = Flat.Ink,
            )
            Row {
                Text(
                    "远端",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Flat.Accent,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "连接到 DSH 宿主 · 配对一次长期有效",
                fontSize = 12.sp,
                color = Flat.Muted,
            )

            Spacer(Modifier.height(36.dp))

            FlatTextField(
                label = "主机地址",
                value = host,
                onValueChange = { host = it },
                placeholder = "https://your-host.example.com:47030",
                icon = GlobeIcon,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(22.dp))

            FlatSegmented(
                options = listOf("配对链接", "粘贴 Cookie"),
                selectedIndex = modeIndex,
                onSelect = { modeIndex = it },
            )

            Spacer(Modifier.height(18.dp))

            if (modeIndex == 0) {
                FlatTextField(
                    label = "配对链接（桌面端「远程访问」面板复制）",
                    value = tokenUrl,
                    onValueChange = { tokenUrl = it },
                    placeholder = "https://…/?pair=… 或 ?token=…",
                    icon = LinkIcon,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                FlatTextField(
                    label = "Cookie 值",
                    value = manualCookie,
                    onValueChange = { manualCookie = it },
                    placeholder = "dsh_pair=… 或 v1.xxx.yyy",
                    icon = LinkIcon,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlatSwitch(on = trust, onToggle = { trust = !trust })
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("信任该主机证书", fontSize = 14.sp, color = Flat.Ink)
                    Text(
                        "樱花 frp 等自签 https 需开启",
                        fontSize = 11.sp,
                        color = Flat.Muted,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            val secret = if (modeIndex == 0) tokenUrl else manualCookie
            FlatButton(
                onClick = { vm.connect(host, secret, modeIndex == 0, trust) },
                enabled = !state.busy && host.isNotBlank() && secret.isNotBlank(),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.2.dp,
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text("连接中…", fontSize = 15.sp, color = Flat.Ink)
                } else {
                    Text(
                        "连 接",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.busy || host.isBlank() || secret.isBlank()) {
                            Flat.Muted
                        } else {
                            Flat.White
                        },
                    )
                }
            }

            state.error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = Flat.Danger, fontSize = 12.sp)
                if (it.contains("Cert") || it.contains("SSL", ignoreCase = true) ||
                    it.contains("Trust anchor", ignoreCase = true)
                ) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "↑ 证书问题：打开上方「信任该主机证书」再点连接",
                        color = Flat.Accent,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(30.dp))
            Text(
                "配对链接短期有效，过期或已使用请回桌面端重新复制。" +
                    "含 ?pair= 的链接走 dsh-web-all 0.3.12 的手机通道（/remote，功能完整）；" +
                    "含 ?token= 的启动链接走核心通道。",
                fontSize = 11.sp,
                lineHeight = 19.sp,
                color = Flat.Muted,
            )
        }
    }
}
