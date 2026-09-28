package com.example.qlapp.games

import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import kotlin.math.*

val SpaceInk=Color(0xFF081624)
val Ice=Color(0xFF99EADF)
val Gold=Color(0xFFEBD09B)
val Peach=Color(0xFFFFC497)
fun GameKind.accent()=when(this){GameKind.AIR->Ice;GameKind.PIANO->PianoTheme.holdTop;GameKind.RUNNER->Peach}
fun GameKind.ink()=when(this){GameKind.AIR->SpaceInk;GameKind.PIANO->PianoTheme.skyMid;GameKind.RUNNER->Color(0xFF422C43)}
/** 在各自 ink 底色上可读的主文字色。三种玩法现在都是深底，统一用白字。 */
fun GameKind.onInk()=Color.White
fun GameKind.onInkMuted()=Color.White.copy(alpha=.65f)
fun GameKind.onInkSoft()=Color.White.copy(alpha=.8f)

/**
 * 曲目模式的配色。背景、方块、长按条、判定线的颜色全在这一个对象里，
 * 想换成别的色调（比如浅底黑块）只改这里就够了，其它代码不用动。
 */
object PianoTheme {
    // 官方那种深蓝紫夜空：顶部最亮，中段压深，底部几乎近黑。
    val skyTop    = Color(0xFF2B3E96)
    val skyMid    = Color(0xFF1A2350)
    val skyLow    = Color(0xFF0B0E22)
    val haloTop   = Color(0xFF7D9BFF) // 顶部光晕
    val haloLow   = Color(0xFF8B5FD6) // 底部侧光晕
    val lane      = Color.White.copy(alpha = .06f)
    // 普通方块：瓷砖质感，上亮下暗。
    val tileTop   = Color(0xFFFFFFFF)
    val tileBottom= Color(0xFFCBD3EC)
    // 长按条：官方用的是鲜艳的暖色渐变胶囊。
    val holdTop   = Color(0xFFFFD86B)
    val holdBottom= Color(0xFFFF6FA5)
    val spark     = Color(0xFFDCE6FF)
}

private fun DrawScope.panel(color:Color,x:Float,y:Float,w:Float,h:Float,r:Float=8f)=drawRoundRect(color,Offset(x,y),Size(w,h),CornerRadius(r))
private fun DrawScope.line(color:Color,x:Float,y:Float,x2:Float,y2:Float,width:Float=1f)=drawLine(color,Offset(x,y),Offset(x2,y2),width,StrokeCap.Round)
private fun DrawScope.shape(color:Color,vararg points:Float){val p=Path();p.moveTo(points[0],points[1]);for(i in 2 until points.size step 2)p.lineTo(points[i],points[i+1]);p.close();drawPath(p,color)}
private val lettering=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)}
private fun DrawScope.letter(s:String,x:Float,y:Float,size:Float,color:Color){lettering.color=color.toArgb();lettering.textSize=size;drawContext.canvas.nativeCanvas.drawText(s,x,y,lettering)}
private fun DrawScope.glow(x:Float,y:Float,r:Float,color:Color){drawCircle(Brush.radialGradient(listOf(color.copy(alpha=.24f),Color.Transparent),Offset(x,y),r),r,Offset(x,y))}

/** Original vector artwork, rendered in the same 400 x 720 space as collisions. */
fun DrawScope.drawGame(model:GameModel,bounds:Rect=Rect(0f,0f,400f,720f)){
    when(model){
        is AirGame->space(model,bounds)
        is SongPianoGame->songPiano(model)
        is PianoGame->piano(model)
        is RunnerGame->city(model,bounds)
    }
    for(p in model.sparks){
        val c=when{
            model.kind==GameKind.PIANO->PianoTheme.spark
            p.gold->Gold
            else->Ice
        }
        drawCircle(c.copy(alpha=(p.life/.8f).coerceIn(0f,1f)),2.3f,Offset(p.x,p.y))
    }
}

fun DrawScope.ship(x:Float,y:Float,scale:Float=1f,enemy:Boolean=false,heavy:Boolean=false,time:Float=0f){
    withTransform({translate(x,y);scale(scale,scale,Offset.Zero);if(enemy)rotate(180f,Offset.Zero)}){
        val base=if(enemy)Color(0xFFAF567A) else Color(0xFFAFD9E0)
        val edge=if(enemy)Peach else Ice
        val flame=18+sin(time*37)*6
        glow(0f,32f,34f,edge)
        shape(edge.copy(alpha=.4f),-7f,21f,0f,38+flame,7f,21f)
        shape(Color(0xFFF9EECA),-3f,20f,0f,30+flame/2,3f,20f)
        // Swept wings, dark nacelles, fuselage, glass canopy and engraved panels.
        shape(Color(0xFF36526D),0f,-30f,-29f,22f,-11f,18f,0f,9f,11f,18f,29f,22f)
        shape(base,-3f,-22f,-34f,16f,-31f,23f,-9f,12f,0f,8f,9f,12f,31f,23f,34f,16f,3f,-22f)
        shape(base.copy(alpha=.7f),-7f,5f,-19f,30f,-8f,25f,0f,17f,8f,25f,19f,30f,7f,5f)
        shape(Color(0xFFECF1E5),0f,-36f,-8f,-10f,-7f,23f,0f,27f,7f,23f,8f,-10f)
        shape(Color(0xFF2B5A78),0f,-20f,-5f,-7f,-4f,7f,4f,7f,5f,-7f)
        line(edge,-22f,9f,-12f,-3f,2f);line(edge,22f,9f,12f,-3f,2f)
        line(Color(0xFF557287),-14f,14f,-27f,19f);line(Color(0xFF557287),14f,14f,27f,19f)
        panel(Color(0xFF314457),-23f,4f,5f,18f,2f);panel(Color(0xFF314457),18f,4f,5f,18f,2f)
        drawCircle(edge,2.2f,Offset(-20.5f,5f));drawCircle(edge,2.2f,Offset(20.5f,5f))
        if(heavy){panel(Color(0xFFB97C63),-34f,5f,10f,26f,3f);panel(Color(0xFFB97C63),24f,5f,10f,26f,3f)}
    }
}

private fun DrawScope.space(s:AirGame,bounds:Rect){
    drawRect(Brush.verticalGradient(listOf(Color(0xFF071323),Color(0xFF183548),SpaceInk),bounds.top,bounds.bottom),topLeft=bounds.topLeft-Offset(12f,0f),size=Size(bounds.width+24f,bounds.height))
    glow(345f,235f,250f,Color(0xFF59BFAE));glow(-60f,560f,240f,Color(0xFF497EAB))
    drawCircle(Color(0xFF274153),82f,Offset(325f,178f));drawCircle(Color(0xFF102637),80f,Offset(341f,166f))
    drawArc(Color(0xFF9DE5DC).copy(alpha=.15f),0f,360f,false,Offset(219f,117f),Size(215f,88f),style=Stroke(2f))
    repeat(72){i->val depth=i%4+1;val xx=(i*137%400).toFloat();val yy=bounds.top+(i*97+s.elapsed*(12+depth*9))%bounds.height;drawCircle(Color.White.copy(alpha=.18f+depth*.1f),if(depth==4)1.5f else .8f,Offset(xx,yy))}
    repeat(9){i->val yy=(i*98+s.elapsed*26)%800-60;line(Ice.copy(alpha=.04f),0f,yy,400f,yy)}
    for(d in s.supplies){glow(d.x,d.y,28f,Gold);drawCircle(SpaceInk,15f,Offset(d.x,d.y));drawCircle(Gold,15f,Offset(d.x,d.y),style=Stroke(1.5f));letter(listOf("P","S","+")[d.type],d.x-5,d.y+5,14f,Gold)}
    for(b in s.shots){line(Ice.copy(alpha=.2f),b.x,b.y+12,b.x,b.y-14,8f);line(Color(0xFFEDFFF4),b.x,b.y+8,b.x,b.y-12,2.5f)}
    for(b in s.threats){drawCircle(Color(0xFFE47A9D).copy(alpha=.2f),10f,Offset(b.x,b.y));drawCircle(Peach,4.5f,Offset(b.x,b.y));drawCircle(Color.White,1.8f,Offset(b.x,b.y))}
    for(e in s.enemies)ship(e.x,e.y,if(e.heavy).93f else .67f,true,e.heavy,s.elapsed)
    s.boss?.let{b->ship(b.x,b.y,1.85f,true,true,s.elapsed);panel(Color(0xFF354A58),90f,s.arenaTop+174f,220f,5f,2f);panel(Peach,90f,s.arenaTop+174f,220f*(b.hp.toFloat()/b.maxHp).coerceIn(0f,1f),5f,2f);letter("WARDEN / 星域守卫",136f,s.arenaTop+166f,10f,Peach)}
    if(s.invulnerable<=0||sin(s.elapsed*35)>-.1f)ship(s.x,s.y,1f,false,false,s.elapsed)
    if(s.shield>0){glow(s.x,s.y,48f,Ice);drawCircle(Ice.copy(alpha=.55f),41f,Offset(s.x,s.y),style=Stroke(1.6f))}
    if(s.flash>0){drawCircle(Ice.copy(alpha=s.flash*.5f),(1-s.flash/.6f)*650,Offset(s.x,s.y),style=Stroke(18f));drawRect(Ice.copy(alpha=s.flash*.2f),topLeft=bounds.topLeft,size=bounds.size)}
}

/** 街机模式：和曲目模式同一套 PianoTheme —— 深蓝紫夜空 + 白色瓷砖方块。 */
private fun DrawScope.piano(s:PianoGame){
    drawRect(Brush.verticalGradient(listOf(PianoTheme.skyTop,PianoTheme.skyMid,PianoTheme.skyLow),0f,720f),size=Size(400f,720f))
    glow(200f,-30f,330f,PianoTheme.haloTop);glow(345f,600f,250f,PianoTheme.haloLow)
    repeat(46){i->val depth=i%3;val xx=(i*83%400).toFloat()
        val yy=110f+((i*137f+s.elapsed*(10+depth*7))%559f)
        drawCircle(Color.White.copy(alpha=.10f+depth*.07f),if(depth==2)1.6f else 1f,Offset(xx,yy))
    }
    clipRect(0f,110f,400f,669f){
        repeat(3){i->val x=(i+1)*100f;drawRect(PianoTheme.lane,Offset(x-.5f,110f),Size(1f,559f))}
        // 最下面一行淡淡提亮，呼应「只按最下面那块」。
        drawRect(Brush.verticalGradient(listOf(Color.Transparent,Color.White.copy(alpha=.07f)),529.25f,669f),
            Offset(0f,529.25f),Size(400f,139.75f))
        for(t in s.tiles){
            if(t.y>669f||t.y+s.rowHeight<110f)continue
            val xx=t.col*100f+6f
            val yy=t.y+3f
            val ww=88f
            val hh=s.rowHeight-6f
            if(t.hit){
                drawRoundRect(Color.White.copy(alpha=.14f),Offset(xx,yy),Size(ww,hh),CornerRadius(9f))
            }else{
                drawRoundRect(Brush.verticalGradient(listOf(PianoTheme.tileTop,PianoTheme.tileBottom),yy,yy+hh),
                    Offset(xx,yy),Size(ww,hh),CornerRadius(9f))
            }
        }
        s.wrongColumn?.let{drawRect(Color(0xFFE5484D).copy(alpha=.26f),Offset(it*100f,110f),Size(100f,559f))}
    }
}

/**
 * 曲目模式的画面：深蓝紫渐变夜空 + 瓷砖质感的白色方块 + 胶囊状的长按条。
 * 长方块按住的时候底边会被「吃掉」，看起来就像一边按一边被消耗掉。
 * 已经按过的方块不直接消失，而是原地轻轻淡出就没了（不留残影）。
 */
private fun DrawScope.songPiano(s:SongPianoGame){
    drawRect(Brush.verticalGradient(listOf(PianoTheme.skyTop,PianoTheme.skyMid,PianoTheme.skyLow),0f,720f),size=Size(400f,720f))
    // 顶部一团圆光 + 底部一侧的紫色光，是官方背景的层次来源。
    glow(200f,-30f,330f,PianoTheme.haloTop);glow(345f,600f,250f,PianoTheme.haloLow)
    // 缓慢上浮的星点，让静态的渐变有一点呼吸感。
    repeat(46){i->val depth=i%3;val xx=(i*83%400).toFloat()
        val yy=PianoLanes.TOP+((i*137f+s.elapsed*(10+depth*7))%559f)
        drawCircle(Color.White.copy(alpha=.10f+depth*.07f),if(depth==2)1.6f else 1f,Offset(xx,yy))
    }
    clipRect(0f,PianoLanes.TOP,400f,PianoLanes.FLOOR){
        repeat(3){i->val x=(i+1)*100f;drawRect(PianoTheme.lane,Offset(x-.5f,PianoLanes.TOP),Size(1f,PianoLanes.FLOOR-PianoLanes.TOP))}
        // 最下面一行淡淡地提亮一下，呼应别踩白块「点最下面那块」的行概念。
        // 这里刻意不画判定线：判定看的是手指压在哪块上，不是压在哪条线上。
        drawRect(Brush.verticalGradient(listOf(Color.Transparent,Color.White.copy(alpha=.07f)),PianoLanes.BAND,PianoLanes.FLOOR),
            Offset(0f,PianoLanes.BAND),Size(400f,PianoLanes.FLOOR-PianoLanes.BAND))
        // 这里刻意不画任何整列的高亮 / 闪烁：官方画面上除了方块本身就是空的。
        val now=s.nowMs
        for(n in s.notes){
            // 长按条的底边位置分两段：
            //  · 还没按：照常下落，底边沉到屏幕最底下才开始被吃掉；
            //  · 按住了：底边立刻钉在手指压下的位置（pressY），顶边继续往下走 ——
            //    于是条从手指开始一点点变短，走完正好归零，这才是官方的「顺着手指变短」。
            // 这里必须显式截断再算高度：只靠 clipRect 裁的话 h 恒等于整条长度，永远不变短。
            // 普通块不截断 —— 漏了就让它整块滑出屏幕，否则红色漏块提示会被一起吃掉。
            val bottom=when{
                n.isHold&&n.pressY>=0f->n.pressY
                n.isHold->min(n.bottom(now),PianoLanes.FLOOR)
                else->n.bottom(now)
            }
            val top=max(n.top(now),PianoLanes.TOP-n.height)
            if(bottom<=PianoLanes.TOP||top>=PianoLanes.FLOOR)continue
            val h=bottom-top
            if(h<=2f)continue
            val x=n.column*100f+6f
            val w=88f
            when{
                n.isHold&&!n.done->holdTile(x,top,w,h,n.holding)
                // 命中后放一个很短的消失动画，原地淡出就没了。
                n.done->spentTile(x,top,w,h,n.result==Judge.MISS,(now-n.doneMs)/180f)
                else->tapTile(x,top,w,h)
            }
        }
    }
}

/**
 * 普通方块：一块干净的白瓷砖。
 * 官方就是纯色圆角块 —— 没有描边、没有投影、没有外发光，别自己加料。
 */
private fun DrawScope.tapTile(x:Float,top:Float,w:Float,h:Float){
    drawRoundRect(Brush.verticalGradient(listOf(PianoTheme.tileTop,PianoTheme.tileBottom),top,top+h),
        Offset(x,top),Size(w,h),CornerRadius(9f))
}

/**
 * 长按长方块：比普通块长一截的暖色条，按住时整条提亮。
 * 「慢慢变短」是位置逻辑做到的（底边钉住、顶边往下走），这里只负责画。
 */
private fun DrawScope.holdTile(x:Float,top:Float,w:Float,h:Float,active:Boolean){
    drawRoundRect(Brush.verticalGradient(listOf(PianoTheme.holdTop,PianoTheme.holdBottom),top,top+h),
        Offset(x,top),Size(w,h),CornerRadius(9f))
    if(active)drawRoundRect(Color.White.copy(alpha=.20f),Offset(x,top),Size(w,h),CornerRadius(9f))
}

/**
 * 命中后的消失动画：官方就一下很轻的淡出，方块略微散开就没了，不留残影。
 * age 从 0 走到 1；超过 1 之后整块不再绘制。
 */
private fun DrawScope.spentTile(x:Float,top:Float,w:Float,h:Float,missed:Boolean,age:Float){
    if(age>=1f)return
    val fade=1f-age
    val grow=1f+age*.22f // 一边淡一边轻轻散开
    val tint=if(missed)Color(0xFFFF5C7A) else Color.White
    val ww=w*grow
    val hh=h*grow
    drawRoundRect(tint.copy(alpha=(if(missed).5f else .62f)*fade),
        Offset(x+w/2-ww/2,top+h/2-hh/2),Size(ww,hh),CornerRadius(9f))
}

private fun DrawScope.city(s:RunnerGame,bounds:Rect){
    val sky=when(s.zone){1->listOf(Color(0xFF425979),Color(0xFFEF9B8E),Color(0xFFFFD4A0));2->listOf(Color(0xFF513D6B),Color(0xFFDB8597),Color(0xFFEFBB9D));else->listOf(Color(0xFF202D53),Color(0xFF765078),Color(0xFFC98794))}
    drawRect(Brush.verticalGradient(sky,bounds.top,580f),topLeft=bounds.topLeft-Offset(12f,0f),size=Size(bounds.width+24f,bounds.height))
    glow(302f,218f,123f,Peach);drawCircle(Color(0xFFFFD8A8),54f,Offset(302f,218f))
    repeat(4){i->line(sky[1],242f,238+i*8f,361f,238+i*8f,2+i*1.5f)}
    // Three independently scrolling skylines, window lights, aerials and roof rails.
    repeat(3){layer->val spacing=if(layer==0)78f else 94f;val speed=(layer+1)*.55f;val base=400+layer*60f;val color=listOf(Color(0xFF886B85),Color(0xFF675571),Color(0xFF493E59))[layer]
        repeat(9){i->val xx=i*spacing-((s.distance*speed)%(spacing*3))-80;val hh=42+(i*31%95)+layer*12
            panel(color,xx,base-hh,spacing-8,hh.toFloat(),2f)
            line(color,xx+12,base-hh-13,xx+12,base-hh,2f)
            if(layer>0)repeat(4){row->repeat(3){col->if((row+col+i)%3!=0)panel(Peach.copy(alpha=.26f),xx+10+col*17,base-hh+12+row*19,5f,9f,1f)}}
        }
    }
    repeat(3){i->val bx=((i*171-s.distance*.5f)%550+550)%550-60;val by=285f+i*18;line(Color(0xFF67506C),bx,by,bx+5,by-3);line(Color(0xFF67506C),bx+5,by-3,bx+10,by)}
    drawRect(Color(0xFF302C44),Offset(bounds.left-12f,580f),Size(bounds.width+24f,bounds.bottom-580f))
    line(Peach,0f,581f,400f,581f,3f);line(Color(0xFF815775),0f,589f,400f,589f,5f)
    repeat(12){i->val xx=i*56-(s.distance*15%56);panel(Color(0xFF4A3A52),xx,605f,40f,4f,1f);panel(Color(0xFF3C324A),xx+15,628f,30f,3f,1f)}
    for(c in s.coins){glow(c.x,c.y,16f,Gold);drawCircle(Gold,8f,Offset(c.x,c.y),style=Stroke(2f));shape(Gold,c.x,c.y-4,c.x+3,c.y,c.x,c.y+4,c.x-3,c.y)}
    for(o in s.obstacles){
        if(o.beam){panel(Color(0xFF5A405F),o.x,505f,o.width,35f,5f);panel(Color(0xFFD9988A),o.x+5,510f,o.width-10,4f,2f);letter("↓",o.x+31,532f,18f,Gold)}
        else{panel(Color(0xFFAB777B),o.x,538f,o.width,42f,3f);line(Color(0xFFFFCAA1),o.x+4,541f,o.x+30,541f,2f);line(Color(0xFF764B62),o.x+4,546f,o.x+30,575f,2f);line(Color(0xFF764B62),o.x+30,546f,o.x+4,575f,2f)}
    }
    drawOval(Color.Black.copy(alpha=.18f),Offset(66f,574f),Size(53f,7f))
    if(s.invulnerable<=0||sin(s.elapsed*30f)>0)runner(93f,580-s.height,s.elapsed,s.height>0,s.isSliding)
}

fun DrawScope.runner(x:Float,ground:Float,time:Float,airborne:Boolean=false,slide:Boolean=false){
    withTransform({translate(x,ground);if(slide){translate(0f,-10f);rotate(-70f,Offset(0f,-12f))}}){
        val step=if(airborne).6f else sin(time*17)
        // Shoes and articulated legs.
        line(Color(0xFF292D46),-2f,-24f,-9f+step*10,-13f,7f)
        line(Color(0xFF292D46),-9f+step*10,-13f,-10f-step*8,-3f,6f)
        line(Color(0xFF3B3652),5f,-23f,10f-step*8,-12f,7f)
        line(Color(0xFF3B3652),10f-step*8,-12f,13f+step*7,-3f,6f)
        line(Color(0xFFEED6B4),-13f-step*8,-2f,-4f-step*8,-2f,4f);line(Color(0xFFEED6B4),9f+step*7,-2f,20f+step*7,-2f,4f)
        panel(Color(0xFF3C737A),-17f,-47f,12f,22f,4f);panel(Color(0xFFCC9C76),-19f,-44f,5f,15f,2f)
        panel(Color(0xFFEFAD79),-9f,-48f,21f,28f,6f)
        line(Color(0xFFDA966A),6f,-42f,14f+step*5,-31f,6f);line(Color(0xFFFFCFA3),14f+step*5,-31f,22f,-37f,4f)
        // Flying scarf and head silhouette.
        val scarf=Path().apply{moveTo(-6f,-47f);cubicTo(-22f,-42f,-31f,-56f+step*4,-40f,-47f);lineTo(-36f,-43f);cubicTo(-23f,-49f,-20f,-35f,-6f,-41f);close()}
        drawPath(scarf,Color(0xFFE66C81))
        drawCircle(Color(0xFFFFD0A0),10.5f,Offset(4f,-57f));shape(Color(0xFF323047),-8f,-54f,-7f,-66f,1f,-71f,13f,-65f,16f,-59f,3f,-61f,-1f,-53f)
        drawCircle(Color(0xFF282A40),1.4f,Offset(11f,-56f));line(Color(0xFFDF687C),-7f,-47f,12f,-47f,4f)
    }
}
