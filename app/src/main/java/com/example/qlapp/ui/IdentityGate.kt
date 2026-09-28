package com.example.qlapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.qlapp.data.GomokuStanding

/** 仅有的两个身份，顺序就是界面上的先后。 */
val CoupleIdentities = listOf("小江", "甜甜")

/**
 * 把后端返回的「本机胜场 / 对方胜场」换算成固定顺序的排行。
 *
 * 后端只给两个数字（本机视角），不知道名字；本机昵称就是自己的身份，
 * 所以在这里把数字对回到人头上。顺序永远是 小江、甜甜 ——
 * 两个人打开同一张卡看到的是同一个排列，而不是各看各的。
 *
 * 昵称不是这两个（理论上不会发生，身份页只给这两个选项）时，
 * 对不上号的那份按 0 算，卡片上也不会乱标领先者。
 */
internal fun buildGomokuStanding(myName: String, mine: Int, theirs: Int): GomokuStanding {
    val me = myName.trim()
    val wins = HashMap<String, Int>()
    wins[me] = mine
    CoupleIdentities.firstOrNull { it != me }?.let { wins[it] = theirs }
    return GomokuStanding(CoupleIdentities.map { it to (wins[it] ?: 0) })
}

/**
 * 第一次进软件的身份选择。
 *
 * 两个人共用同一份云端数据，但每台手机得先认领自己是谁 ——
 * 任务、照片、留言的作者都取这个名字。只给「小江」「甜甜」两个选项、
 * 不给自由输入，免得两边各写各的、名字对不上。
 *
 * 选完就写进本地昵称（并同步到云端，管理网页能看到），之后不再出现。
 */
@Composable
fun IdentityGate(onPick: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
        ) {
            Text(
                text = "小江 & 甜甜",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "第一次进来，先认领你是谁",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            CoupleIdentities.forEach { name ->
                Button(
                    onClick = { onPick(name) },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                ) {
                    Text(
                        text = "我是$name",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "选完就是你在这台手机上的身份，任务、照片和留言都会记在这个名字下。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
