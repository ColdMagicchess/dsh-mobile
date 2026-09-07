# 粒子消散/汇聚转场 · 实现规范

> 本文是给智能体/工程师的**跨项目复刻指南**：不依赖本仓库代码即可完整实现
> 「面板化为粒子消散、粒子汇聚重铸面板」的鸿蒙 7 风格转场。
> 所有公式、常量、时序均为线上调优后的实测值，可直接照搬。
>
> 本仓库内两份参考实现：
> - Web/Canvas 2D：根目录 `particle-window.html`（玻璃窗口整块粒子化，暗黑背景 + 加法发光，适合演示）
> - Android/Compose：`app/src/main/java/com/example/DSH_Mobile/ui/DrawerParticles.kt` + `ChatScreen.kt`
>   （抽屉三点侵蚀版，浅色背景下的工程化形态，本文以这一版为主描述）

---

## 0. 效果定义

| 相位 | 观感 |
|---|---|
| **汇聚（换入）** | 粒子流从左侧飞入、按左→右波次掠过；面板以显影波逐列清晰成形；汇聚完位图淡出交棒真实组件（阴影渐显）|
| **消散（换出）** | 面板从 3 个随机种子点开洞、侵蚀圆**匀速**外扩；洞缘所到之处的像素被释放为粒子，整体向左飘散、渐隐 |
| **反向打断** | 任意时刻可反转方向：面板直接退场，粒子以当前位置为起点续飞，不跳变 |

核心原则（两条，违反必翻车）：
1. **面板本体永远以清晰位图绘制**，粒子只出现在"侵蚀边界/入射流"上——整块面板粒子化飞行 = 满屏马赛克糊感；
2. **波前与粒子释放时刻严格同步**（同一距离函数），否则出现"洞过去了粒子还在"或"粒子飞了面板没跟上"。

---

## 1. 总体架构

```
触发(点击/手势)
  │
  ├─ ① 录制：把目标面板画进离屏层 → 回读为位图（GPU 回读）
  ├─ ② 采样：位图 → 网格采样为粒子数组（位置/颜色/随机数）＋选 3 个侵蚀种子点   ← 放后台线程！
  ├─ ③ 参数化：给每粒子算 延迟/时长/起点偏移（只依赖 home 坐标与种子距离）
  └─ ④ 动画：全屏粒子画布每帧解算 pos=f(i,t)，面板位图 + 遮罩同帧绘制
       汇聚：drawBitmap + DST_IN 线性显影波 + 粒子(左→右, sin包络)
       落位：位图 alpha 1→0，真实组件在底下完成首绘接管
       消散：drawBitmap + 3×DST_OUT 侵蚀圆 + 粒子(左飘, 渐隐)
```

状态机（Android 版实现，Web 版同理简化）：

```
Closed ──reqOpen()──> [录制] ──> Converging ──tConverge──> Open(+落位段450ms) ──> Open
Open   ──reqClose()─> [录制] ──> Dispersing  ──tDisperse──> Closed
Converging ──reqClose()──> Dispersing(fromCurrent)    // 面板退场，粒子快照位置续飞
Dispersing ──reqOpen()──> Converging(fromCurrent)
录制失败/超时 → 直接跳目标态（功能永远可用，动效自动降级）
```

---

## 2. 录制与采样

### 2.1 录制（面板 → 位图）
- 在触发瞬间把「面板整棵子树的绘制」重定向进一个离屏图形层，回读为位图。
- **Android 坑**：必须用 `ContentDrawScope` 扩展 `layer.record(size) { drawContent() }`
  （录制期间把本作用域画布重定向进 layer）。直接调成员版 `record(density, layoutDirection, size){}`
  时块内 `drawContent()` 画的是**屏幕画布**，layer 是空的。
- **Android 坑**：`toImageBitmap()` 返回 HARDWARE 位图，`getPixels` 直接抛异常——
  先 `copy(Bitmap.Config.ARGB_8888, false)` 拷成软件位图。
- **Web 版**：离屏 canvas 合成面板后 `getImageData` 即可，天然软件像素。
- 录制帧不显示真实面板（汇聚场景面板本来就不可见；消散场景照常绘制，录制对用户不可见）。

### 2.2 采样（位图 → 粒子）
```
step   = max(7, ceil(sqrt(W*H / TARGET)))     // TARGET=8000；面板 666×1600 → step≈12
for y in 0..H step, x in 0..W step:
    c = pixel(x,y);  if (alpha(c) < 26) skip  // 圆角外透明像素
    home=(x,y), color=c, rn=rand(), seed=rand()*100
容量 CAP=10000；粒子数组用 SoA（FloatArray 分列），杜绝对象分配
```
- **必须后台线程**：`copy` + `getPixels` + 百万像素循环放 `Dispatchers.Default`/Worker，
  否则点击后主线程冻结 100~300ms（用户第一句反馈就是"顿一下"）。
- 颜色直接存 ARGB Int，绘制时逐粒子 `drawRect(color, home+offset, step×step, alpha)`。

### 2.3 侵蚀种子点（每次转场重新随机）
```
for k in 0..2:
    seedX[k] = W * (0.12 + 0.76 * ((k + rand()) / 3))   // 横向分层，避免扎堆
    seedY[k] = H * (0.12 + 0.76 * rand())
maxReach = max over particles( minDist(home, seeds) ) * 1.12 + 40   // 保证波前可达所有像素
```

---

## 3. 消散（换出）数学

```
// 释放时刻：洞缘到达该像素的瞬间（+小抖动）
delay[i] = minDist(home_i, seeds) / maxReach * wave + rn[i] * 0.05      // wave = 0.5s

// 面板遮罩：saveLayer 内 drawBitmap 后，3 个 DST_OUT 软边圆
r(t)  = clamp(t / wave, 0, 1) * maxReach          // ★ 线性匀速！
edge  = r * 0.22                                   // 羽化带：径向渐变 [白,白,透明]@[0,.72,1]

// 粒子飞行（age = t - delay，k = age / life，life = 0.40 + rn*0.18）
e = k*k                                            // easeIn 加速左飘
x = hx - flow*e + sin(t*6 + seed)*10*k             // flow = 360 + rn*560 + (W-hx)*0.35
y = hy - (15 + rn*55)*e + cos(t*5 + seed*1.3)*8*k  // 轻微上浮 + 湍流
α = (1-k) * (0.88 + 0.12*sin(t*15 + seed*29))      // 渐隐 + 轻闪烁
sz = step                                          // ★ 满铺不缩尺寸
```

两个关键决策（都被实测打脸过）：
- **侵蚀波必须线性匀速**。用 easeOutQuad（前快后慢）会在前 0.25s 吃掉 90% 面板——
  用户只看得见"碎掉"看不见"侵蚀"，抱怨"太快看不清"。
- **粒子绝不缩尺寸**。缩小会让满铺网格露出缝隙 → 整片看起来是离散方块（"很模糊"）。
  保持 step 满铺、只降 alpha，粒子带就是连续绸缎。

---

## 4. 汇聚（换入）数学

```
// 粒子：自左飞入，落位波 = 显影波的同一条距离函数（保证同步）
delay[i] = (hx[i] / W) * 0.22 + rn[i] * 0.05       // 左→右波次
dur[i]   = 0.26 + rn[i] * 0.10
start    = (hx - (40 + rn*260),  hy + (rn-0.5)*90) // 起点在原点左侧+纵向散布
p = (t - delay)/dur;  e = easeOutCubic(p)
pos = home + start_offset * (1-e)
α   = sin(π * p)                                   // ★ 包络：起飞淡入、落位淡出融入面板

// 面板显影波：saveLayer 内 drawBitmap 后，一个 DST_IN 线性渐变全屏矩形
edge(t) = -feather + (W + 2*feather) * clamp((t - 0.18)/0.36, 0, 1)     // feather=110px
// 渐变 [白,透明] 从 edge-feather 到 edge+feather：左侧保留、右侧抹除 → 面板从左到右显影
```

落位时刻 ≈ 0.22+0.36=0.58s，显影完成 0.54s——显影波恰好压在落位波后面 0~0.04s，
像素"被粒子推进来、在波前下就位"。总时长 tConverge=0.72s。

---

## 5. 落位段（交棒动画）——消除收尾顿帧

**问题**：录制位图 ≠ 真实组件：
- 位图被 saveLayer 裁掉了**外投影**（elevation/box-shadow 是渲染节点属性，录不进去）→ 位图"平"；
- 真实组件首帧要构建显示列表（还可能含背景模糊）→ 直接切换 = 每次都能数出来的"卡一下"。

**方案**：汇聚完成后别急着切——
```
1. dPhase 置 Open（真实组件在位图底下挂载并完成昂贵首绘，用户看不见）
2. 位图保持原样，alpha 1→0 线性淡出（SETTLE=450ms）
3. 面板右缘柔影全程预铺（与真阴影同参数），切完自然衔接
```

**必须验证动画真的在播**（Android 血泪）：驱动循环写进度状态时
```
animT = tConverge            // ✗ 每帧同值 → Compose 不失效 → Canvas 不重绘 → 整段没播
animT = tConverge + u        // ✓ 携带每帧变化
```
写同值不触发重绘，表现是"位图原地滞留 450ms 后瞬间消失"——比没做交棒还像卡顿。

---

## 6. 反向打断

参数化动画的红利：任意时刻可反转，无需重新录制——
```
1. 用当前相位公式解算每粒子此刻位置 pos_i(t_now)
2. 以 (pos_i - home_i) 作为新相位的起点偏移（汇聚起点=该偏移；消散 base offset=该偏移）
3. panelMode 置 0（面板直接退场，避免半显影/半侵蚀状态不连续），清零进度后重启驱动
```

---

## 7. 参数表（实测推荐值）

| 参数 | 推荐 | 含义 |
|---|---|---|
| TARGET / CAP | 8000 / 10000 | 粒子数（中端设备流畅档；2.4 万会掉帧）|
| step 下限 | 7px | 采样网格（实际 ≈ sqrt(面积/TARGET)）|
| alpha 阈值 | 26 | 透明像素跳过 |
| wave | 0.5s | 侵蚀波扫过全卡 |
| 粒子寿命(消散) | 0.40+rn·0.18s | 洞缘释放后的渐隐时长 |
| flow(消散) | 360+rn·560+dx·0.35 px | 左飘行程 |
| reveal 起点/时长 | 0.18s / 0.36s | 显影波 |
| 落位波系数 | 0.22 + rn·0.05 | delay = (x/W)·0.22+… |
| 飞行时长(汇聚) | 0.26+rn·0.10s | 与显影波压 ≤0.04s 相位差 |
| tConverge / tDisperse | 0.72s / 1.05s | 相位总时长 |
| SETTLE | 450ms | 位图交棒淡出 |
| feather | 110px / 0.22r | 显影/侵蚀羽化带 |
| 阴影 | 粒子 alpha>0.15 才画，偏移(+1.5,+2.5)，α×0.15 | 浅背景可见性 |

---

## 8. 平台映射

| 能力 | Web (Canvas 2D) | Android (Compose) |
|---|---|---|
| 面板录制 | 离屏 canvas 合成 + getImageData | GraphicsLayer + `record(size){drawContent()}` 扩展 + toImageBitmap + copy(ARGB_8888) |
| 挖洞/显影 | destination-out / destination-in 径向渐变 | nativeCanvas saveLayer + PorterDuff DST_OUT/DST_IN + Radial/LinearGradient |
| 逐帧驱动 | rAF timestamp | LaunchedEffect + withFrameMillis，进度状态必须携带变化 |
| 后台采样 | 主线程即可（或 OffscreenCanvas/Worker） | Dispatchers.Default（必须）|
| 发光策略 | 暗背景 → `lighter` 加法混合 + 亮度补偿 | 浅背景 → 普通混合 + 粒子柔影 |
| 不可见≠不可交互 | — | 组合中的隐藏面板要挂 disabled clickable 吞触摸；且只能在"内容已组合但不可见"相位挂，Closed 挂会吃掉触发按钮 |

Compose 版本注意：`GraphicsLayer` 录制/读回需 Compose ≥1.8（BOM ≥2025.06）；
`rememberGraphicsLayer` 在 `androidx.compose.ui.graphics` 包（不在 `.layer` 子包）；
1.9 无 `DrawScope.drawLayer/drawContext` 扩展时直接走 `scope.drawContext.canvas.nativeCanvas` + PorterDuff，最稳。

---

## 9. 踩坑清单（每一条都是真实事故）

1. **首帧闪烁**：相位切换时动画状态残留上一轮结束值，第一帧画出"完成态"→ 切相位必须与进度清零**同一快照**提交。
2. **协程自杀**：`LaunchedEffect(flag){ …; flag=false }` 改 key 会取消正在跑的自己 → 用只增计数当 key，另设非 key 开关。
3. **常驻隐形层吞点击**：跳过绘制不阻止命中测试；全屏 disabled clickable 会消费点击。隐藏面板要么不组合，要么精确挂拦截。
4. **HARDWARE 位图**：GPU 回读位图不能 getPixels → copy 成 ARGB_8888。
5. **record 画布重定向**：成员版 record 的块内 drawContent 画到屏幕、layer 为空 → 用 ContentDrawScope 扩展版。
6. **elevation/box-shadow 录不进位图** → 位图"平"、切换"跳"：靠落位段交棒 + 预铺边缘阴影解决。
7. **主线程采样卡顿**：百万像素 copy+getPixels 挪后台。
8. **easeOutQuad 侵蚀波**：前快后慢，90% 面板 0.25s 内消失 → 线性匀速才看得清。
9. **粒子缩尺寸**：露网格缝 = 马赛克感 → 满铺只降 alpha。
10. **进度状态写同值**：不触发重绘，整段动画没播（见 §5）。
11. **数据时序**：列表刷新要在录制**之前**触发，否则交棒瞬间"位图内容 ≠ 实时内容"又跳一次。
12. **动画降级兜底**：录制/回读失败或超时（1.5s）→ 直接切换目标态，功能永远可用。

---

## 10. 验收清单

- [ ] 换入：无首帧闪烁；面板逐列清晰显影（全程无整块马赛克）；粒子从左飞入并"融入"面板
- [ ] 换入收尾：位图淡出 + 阴影渐显交棒，肉眼无跳帧/无"补阴影"突跳
- [ ] 换出：三个洞缘匀速外扩清晰可辨；粒子带连续向左飘散；总时长 ≤1.2s
- [ ] 中途反向：粒子从当前位置续飞，无位置跳变
- [ ] 连开连关 10 次无掉帧（中端机 60fps）、无卡死；录制失败时自动降级为瞬时切换
- [ ] 隐藏相位期间屏幕其余区域点击全部正常（拦截层时序正确）

---

## 附：最小伪代码（平台无关）

```pseudo
onTrigger(open?)
  bmp   = recordPanel()                      // 离屏录制+回读（后台线程做像素活）
  parts = sampleGrid(bmp, TARGET)            // home/color/rn/seed
  seeds = 3 random points; maxReach = coverAll(parts, seeds)
  for p in parts:
    if open: p.delay=(p.x/W)*0.22+rand*0.05; p.dur=0.26+rand*0.10; p.off=(-40-rand*260, ±45)
    else:    p.delay=minDist(p,seeds)/maxReach*wave+rand*0.05; p.life=0.40+rand*0.18; p.flow=…
  t0 = now()
  every frame t = now()-t0:
    if open:
      layer: draw(bmp, mask = DST_IN linearEdge(t))      // 显影波
      parts: pos = home+off*(1-easeOutCubic(p)); α = sin(π·p)
      if t>tConverge: real=true; fade bmp 1→0 over SETTLE
    else:
      layer: draw(bmp, mask = 3× DST_OUT circle r(t))    // 线性侵蚀
      parts: age>0 → pos=home-flow·age²±turbulence; α=(1-k)·flicker; sz=step
      if t>tDisperse: done
```

—— 完 ——
