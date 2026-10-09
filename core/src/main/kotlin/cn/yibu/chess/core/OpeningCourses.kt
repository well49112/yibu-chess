package cn.yibu.chess.core

/** Authored offline lessons. Every route and checkpoint is checked as legal in the focused tests. */
object OpeningCourses {
    private fun check(ply: Int, prompt: String, hint: String, move: String, why: String) =
        OpeningCheckpoint(ply, prompt, hint, mapOf(move to why))
    private fun route(title: String, moves: String, notes: String, identity: Int, checkpoint: OpeningCheckpoint) =
        OpeningRoute("main", title, moves.split(' '), notes.trimIndent().lines().map(String::trim), checkpoint, identity)
    private fun branch(base: OpeningRoute, title: String, prefix: Int, moves: String, notes: String,
        checkpoint: OpeningCheckpoint, identity: Int = base.identityPlies) =
        OpeningRoute("branch", title, base.moves.take(prefix) + moves.split(' '),
            base.notes.take(prefix) + notes.trimIndent().lines().map(String::trim), checkpoint, identity)

    private val italian = route("温和意大利", "e2e4 e7e5 g1f3 b8c6 f1c4 f8c5 d2d3 g8f6 e1g1 d7d6 c2c3 e8g8 f1e1 a7a6 c4b3 c5a7", """
        e兵占住e4，控制d5和f5；也打开f1象与d1后的出路。
        黑方用e5控制d4和f4，双方都争夺中心。
        马从g1到f3，直接攻击e5兵，同时腾出易位所需的g1格。
        马到c6保护e5兵；Nxe5不再是白吃一个兵，黑马可以回吃。
        象到c4瞄准f7兵，并腾出f1格；白方现在具备王翼易位的基本条件。
        黑象到c5瞄准f2；双方都要在出子时注意王前的薄弱点。
        d3兵保护e4，也打开c1象的斜线；暂时保持中心，准备先安置王。
        黑马到f6攻击e4；e4已经有d3兵保护，不必为这个攻击慌忙移动后。
        王到g1、车到f1，王离开中心；下一阶段再考虑c3与d4的中心推进。
        d6保护e5，打开c8象；黑方也准备易位。
        c3兵控制d4，为之后d3-d4建立支撑；代价是b1马暂时不能走c3。
        黑方完成王翼易位，中心一旦打开，双方的王都已有保护。
        车到e1沿e线保护e4兵，若以后中心交换，车可能参与开放线争夺。
        a6控制b5，也腾出a7作为c5象的退路。
        象撤到b3继续沿b3-c4-d5-e6-f7瞄准f7，不必反复挪动其他已发展的子。
        黑象撤到a7，保存这枚象并继续沿a7-b6-c5-d4-e3-f2斜线活动。
    """, 5, check(8, "先把王安置好，再准备中心推进", "f1象和g1马已经离开底线。", "e1g1", "O-O把王放到g1、车放到f1。e4由d3兵保护，可以先完成王的安全安排。"))
    private val italianBranch = branch(italian, "两马防御：先护住e4", 5, "g8f6 d2d3 f8c5 c2c3 d7d6 e1g1 e8g8", """
        黑方先走Nf6攻击e4，白方不能只记着接下来摆c3。
        d3直接保护e4，同时打开c1象；若黑方Nxe4，d3兵可以回吃。
        黑象到c5瞄准f2，白方应尽快易位而非早早出后。
        c3为d4提供支撑；e4有d3保护，所以这步不会把中心兵直接送掉。
        黑方d6保护e5并打开c8象，接下来可以完成易位。
        白方完成易位，再考虑Re1和d4，先检查黑方有没有针对e4的新攻击。
        黑方也完成易位，后续是双方争夺d4和e5的中局。
    """, check(6, "找一着保护e4、同时打开c1象的兵步", "哪一个兵能走到d3？", "d2d3", "d3兵保护e4，并让c1象可以沿d2-e3方向出动。直接继续摆其他子会忽略黑马对e4的攻击。"))

    private val spanish = route("西班牙：保存象与保护e4", "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6 e1g1 f8e7 f1e1 b7b5 a4b3 d7d6 c2c3 e8g8", """
        e4控制d5和f5，并打开f1象的出路，之后可以配合马发展。
        e5与白方争夺中心，两边的e兵现在都可能成为攻击目标。
        Nf3攻击e5，同时发展王翼马。
        Nc6给e5兵增加保护；白方若Nxe5，黑马可以回吃。
        Bb5攻击保护e5的c6马；这并不意味着下一着吃马再吃兵必然能赢子。
        a6兵攻击b5象，白方需要决定撤象还是交换。
        Ba4保存象，继续沿a4-b5-c6方向压迫黑马。
        Nf6攻击e4；白方准备易位后用车加强保护。
        O-O把王移到g1；黑方若吃e4，仍需计算白方的中心应对，不能只看一着。
        Be7腾出f8格，为黑方易位做准备。
        Re1直接保护e4，车也对准未来可能打开的e线。
        b5兵再次攻击a4象，并扩展后翼空间。
        Bb3保存象，沿通向f7的斜线继续施压。
        d6兵保护e5并打开c8象，后翼轻子也可以逐步参与争夺中心。
        c3准备支持d4；出子和易位已基本完成，现在可以考虑争夺更大的中心。
        黑方易位，双方接下来围绕d4突破和e5支点展开争夺。
    """, 5, check(6, "撤开被a6兵攻击的象，继续沿斜线压迫c6", "保留象，考虑a4格。", "b5a4", "Ba4让象避开a6兵的攻击，并保留对c6马的压力。Bxc6也是另一种开局选择，本题练的是保存象的路线。"))
    private val spanishBranch = branch(spanish, "柏林：先稳住中心", 5, "g8f6 d2d3 f8c5 b5c6 d7c6 e1g1 e8g8", """
        Nf6直接攻击e4；黑方没有先用a6赶象。
        d3保护e4，打开c1象，选择稳固的慢布局。
        黑象到c5瞄准f2，白方仍有易位安排。
        Bxc6交换象和马，移除e5的一枚保护子；并不自动等于赢兵。
        d兵回吃到c6，黑方得到双c兵和双象，双方都有结构与子力上的取舍。
        白方完成易位，再考虑出动c1象和b1马。
        黑方易位；以后攻击双兵时也要注意黑方双象的活动。
    """, check(6, "用兵保护e4并打开后翼象", "d2兵前进一步即可保护e4。", "d2d3", "d3让e4不再悬空，c1象也有出路；可以先巩固中心再完成易位。"))

    private val scotch = route("苏格兰：打开中心", "e2e4 e7e5 g1f3 b8c6 d2d4 e5d4 f3d4 g8f6 d4c6 b7c6 f1d3 d7d5 e4d5 c6d5 e1g1 f8e7", """
        e4控制d5和f5并打开f1象；之后d4推进可以直接挑战黑方中心。
        e5控制d4，使中心突破会伴随吃子。
        Nf3发展王翼马并攻击e5，黑方需要考虑中心兵的保护。
        Nc6保护e5，黑方保持中心支点。
        d4直接挑战e5，准备通过交换打开象与车的线路。
        exd4吃掉白方d兵；白方可以用f3马回吃。
        Nxd4回收黑方e兵，马占据中心；双方各交换了一个中心兵。
        Nf6攻击e4，白方要处理中心而非连续挪动后。
        Nxc6交换两匹马，黑方的回吃会改变兵结构。
        bxc6用b兵回吃，c6兵控制d5，也给黑方提供中心支撑。
        Bd3保护e4并瞄准h7，为白王易位腾出f1。
        d5直接挑战e4，是黑方在开放中心中的重要反击。
        exd5把e4兵换成黑方d兵，中心线路继续打开。
        cxd5回吃后黑方在d5建立支点，c线也失去原来的c6兵。
        O-O让王离开已打开的中心；之后发展c1象和b1马。
        Be7为黑方易位做准备，白方下一阶段可以把车放到开放线。
    """, 5, check(6, "用已经发展的马回收d4兵", "f3马可以走到d4。", "f3d4", "Nxd4回收黑方刚吃到d4的兵，让马留在中心。双方各少一个中心兵，没有凭空赚到一个兵。"))
    private val scotchBranch = branch(scotch, "…Bc5：保护中心马", 7, "f8c5 c1e3 d8f6 c2c3 g8e7", """
        Bc5直接攻击d4马，白方要增加保护或移动这匹马。
        Be3发展c1象并保护d4，白方的出子同时回应威胁。
        Qf6沿f6-e5-d4继续增加压力，也盯着f2，不能忽视王前安全。
        c3兵进一步保护d4，让中心马有支撑。
        Ne7发展黑马并准备易位；白方可以发展f1象，之后把王安置好。
    """, check(8, "发展后翼象，同时给d4马增加保护", "c1象可以沿d2走到e3。", "c1e3", "Be3同时完成两件事：出动c1象，并保护受到c5象攻击的d4马。"))

    private val london = route("伦敦：建立稳固站位", "d2d4 d7d5 c1f4 g8f6 e2e3 e7e6 g1f3 f8d6 f4g3 e8g8 f1d3 c7c5 c2c3 b8c6 b1d2 f8e8", """
        d4控制e5与c5，先建立中心支点。
        d5控制e4与c4，黑方对称争夺中心。
        Bf4趁e2兵还没走到e3，先把c1象放到兵链外。
        Nf6发展并控制e4，黑方可以接着出象或冲击d4。
        e3保护d4并打开f1象；由于c1象已经出来，不会把它锁在兵链里。
        e6保护d5，打开f8象的斜线。
        Nf3保护d4并控制e5，为易位腾出g1。
        Bd6攻击f4象；白方必须处理这次直接攻击。
        Bg3保存象，黑方若Bxg3，h2兵通常可以回吃，但要检查王翼结构。
        黑方易位，白方接下来也应完成王翼出子。
        Bd3发展最后一枚王翼轻子，瞄准h7并为易位腾出f1。
        c5攻击d4，这是黑方挑战伦敦中心的重要兵步。
        c3给d4增加保护；也留意黑后可能去b6攻击b2。
        Nc6发展并继续攻击d4，白方不能因为站位熟悉就停止检查威胁。
        Nbd2发展后翼马，支撑e4推进；下一着可以考虑易位。
        Re8把车放到e线，黑方准备中心反击；白方先完成易位再考虑e4。
    """, 3, check(8, "把受到d6象攻击的f4象撤开，保留这枚象", "g3格可以作为退路。", "f4g3", "Bg3避开d6象的直接攻击。若黑方继续换象，应计算h2兵回吃后的王翼结构。"))
    private val londonBranch = branch(london, "…Qb6：保护b2与换后", 3, "c7c5 e2e3 b8c6 c2c3 d8b6 d1b3 b6b3 a2b3 g8f6", """
        c5直接挑战d4，伦敦站位需要应对中心压力。
        e3兵保护d4，并打开f1象通向d3的斜线，准备完成王翼出子。
        Nc6再攻击d4，黑方的后翼压力很快形成。
        c3稳住d4，但也腾空c2，让白后可以走到b3。
        Qb6沿b线攻击b2，白方不能照计划只出另一匹马。
        Qb3挡住黑后对b2的线路，并提出换后。
        Qxb3交换后，白方可以用a2兵回吃。
        axb3回收黑后，白方出现双b兵但a线打开；这里是结构取舍，没有亏一枚后。
        Nf6继续发展，白方可按Bd3、Nf3、易位的计划完成出子。
    """, check(8, "回应黑后对b2的攻击，并提出换后", "c2已经腾空，d1后能经过c2到b3。", "d1b3", "Qb3用白后挡住b线，并准备Qxb3之后axb3回吃。代价是可能形成双b兵，收益是解除直接威胁并换后。"))

    private val queenGambit = route("后翼弃兵：出子与中心压力", "d2d4 d7d5 c2c4 e7e6 b1c3 g8f6 c1g5 f8e7 e2e3 e8g8 g1f3 h7h6 g5h4 b7b6 f1d3 c8b7", """
        d4占住中心，为c1象腾出通道。
        d5兵控制c4和e4，建立中心支点；也打开c8象的斜线。
        c4攻击d5，准备通过交换改变中心兵结构。
        e6用兵保护d5，但暂时限制c8象；黑方以后要解决这枚象的出路。
        Nc3继续攻击d5，并发展后翼马。
        Nf6保护d5并控制e4，回应白方的中心压力。
        Bg5沿g5-f6-e7-d8瞄准黑后；f6马被相对牵制，不能只看它表面控制的格子。
        Be7挡在后与象之间，准备易位，也减轻f6马受到的牵制。
        e3保护d4并打开f1象；后翼象已经出动，不会被关在e3兵后。
        黑方易位让王离开中心；白方也应及时安置王，再考虑打开中心。
        Nf3继续保护d4，白方也准备王翼易位。
        h6兵攻击g5象，白方需要回应。
        Bh4保存象，继续沿h4-g5-f6方向给黑马压力。
        b6为c8象腾出b7，准备沿长斜线出动。
        Bd3发展王翼象，瞄准h7，为白方易位腾出f1。
        Bb7解决c8象的出路；白方完成易位后可考虑车到c线或e线。
    """, 3, check(12, "撤开受h6兵攻击的象，继续瞄准f6马", "h4格仍在同一条斜线上。", "g5h4", "Bh4让象避开h6兵，继续沿h4-g5-f6施压。换掉f6马也是另一种选择，本题练保存象的路线。"))
    private val queenAccepted = branch(queenGambit, "接受弃兵：先出子再回收", 3, "d5c4 g1f3 g8f6 e2e3 e7e6 f1c4 c7c5 e1g1 a7a6", """
        dxc4暂时吃掉c4兵，黑方中心d5格被腾空。
        Nf3先发展并控制e5，白方无需急着出后追兵。
        Nf6发展黑马并控制e4，准备继续出象与易位。
        e3打开f1象，让它可以沿e2-d3-c4回收兵。
        e6打开黑方王翼象，为易位做准备。
        Bxc4回收c4兵并发展象，白方没有继续亏着这枚兵。
        c5攻击d4，黑方通过中心反击争取活动。
        O-O把王放到g1，接下来出动b1马和c1象。
        a6控制b5，黑方可以考虑b5赶象，白方要留意象的退路。
    """, check(8, "用王翼象回收c4兵，同时完成出子", "e2和d3通道都已腾空。", "f1c4", "Bxc4回收暂时丢掉的兵，并把f1象发展到瞄准f7的斜线上；不用让后过早出动。"))

    private val earlyQueen = branch(italian, "早出后：防四步杀", 2, "d1h5 b8c6 f1c4 g7g6 h5f3 g8f6 g1e2 f8g7 d2d3 e8g8", """
        Qh5攻击e5，并沿h5-g6-f7瞄准f7。
        Nc6先保护e5；马上g6会让白后有Qxe5+的机会。
        Bc4与后共同瞄准f7，白方现在威胁Qxf7#。
        g6堵住h5到f7的斜线，并攻击h5后；先前Nc6已把e5兵护住。
        Qf3转到f线上，白方又威胁沿f3-f4-f5-f6-f7吃f7。
        Nf6堵住f线，同时发展马；d8后沿e7-f6保护这匹马。
        Ne2发展白马，为白方易位做准备，早出后的直接攻势已被挡住。
        Bg7利用g6腾出的g7格出象，黑方准备易位。
        d3保护e4并打开c1象，白方恢复正常出子。
        黑方完成易位，接下来可以走d6并发展c8象。
    """, check(5, "挡住白方Qxf7#，同时赶走h5后", "e5已经由c6马保护，现在可以推进g兵。", "g7g6", "g6兵堵住h5-g6-f7斜线，使Qxf7#路线被截断，同时攻击h5后；这步依赖先前Nc6对e5的保护。"), identity = 3)

    private val caro = route("卡罗康：先出象再建兵链", "e2e4 c7c6 d2d4 d7d5 b1c3 d5e4 c3e4 c8f5 e4g3 f5g6 h2h4 h7h6 g1f3 b8d7 h4h5 g6h7", """
        白方e4控制d5和f5，打开f1象与d1后的出路，黑方需要回应中心争夺。
        c6控制d5，准备下一着用d5兵挑战e4。
        白方d4建立两个中心兵，黑方需要及时反击。
        d5在c6兵保护下直接攻击e4。
        Nc3发展并保护e4，白方也准备回收中心交换。
        dxe4吃掉e4兵，暂时打开中心。
        Nxe4回收兵，白马占据e4，双方各交换一个中心兵。
        Bf5先把c8象放到e6兵链之外，并攻击e4马。
        Ng3攻击f5象，白马也从中心撤走。
        Bg6让象避开g3马，保持这枚象的活动。
        h4准备h5赶象，黑方要给象安排退路。
        h6腾出h7格，让g6象被赶时有地方撤。
        Nf3发展并准备易位，白方已经有空间优势。
        Nd7发展b8马，支撑以后Nf6或e5；黑方还需要出动f8象。
        h5兵攻击g6象，黑方现在必须处理象。
        Bh7撤到先前腾出的h7，保存象；以后e6、Ngf6、Be7、易位完成出子。
    """, 4, check(7, "趁e6还没堵住斜线，把c8象发展出来并攻击e4马", "c8-d7-e6-f5的通道现在是空的。", "c8f5", "Bf5沿斜线攻击e4马，并在走e6之前解决c8象的出路。"))
    private val caroAdvance = branch(caro, "推进变例：冲击d4", 4, "e4e5 c8f5 g1f3 e7e6 f1e2 c6c5 e1g1 b8c6", """
        e5关闭中心，白方取得空间，黑方应攻击兵链根部d4。
        Bf5先出动c8象；若先e6，这枚象会被自己的兵链挡住。
        Nf3保护d4并腾出g1格，为之后出象和易位做准备。
        e6稳住d5并打开f8象，黑方后翼象已经在兵链外。
        Be2腾出f1格，白方准备易位。
        c5直接攻击d4，用兵挑战白方空间，不必把所有子挤在后方。
        白方易位，黑方接着完成轻子发展。
        Nc6发展并增加对d4的攻击；黑方还要安排Nf6或Ne7与易位。
    """, check(5, "在走e6之前，把后翼象放到兵链外", "考虑c8象到f5。", "c8f5", "Bf5避免c8象被随后的e6兵堵住。完成出象后再用e6巩固d5，并用c5冲击d4。"))

    private val french = route("法兰西：攻击兵链根部", "e2e4 e7e6 d2d4 d7d5 b1c3 g8f6 e4e5 f6d7 f2f4 c7c5 g1f3 b8c6 c1e3 c5d4 f3d4 f8c5", """
        白方e4控制d5和f5，打开f1象与d1后的出路，黑方需要回应中心争夺。
        e6准备d5，并打开f8象；同时暂时限制c8象。
        d4建立白方中心兵，黑方要挑战这组兵。
        d5攻击e4，有e6兵在后方支持。
        Nc3发展并保护e4，白方可以选择推进或交换。
        Nf6继续攻击e4，促使白方决定中心结构。
        e5攻击f6马并关闭中心，黑方需要把马撤到合适位置。
        Nd7撤开马，准备配合c5攻击d4。
        f4保护e5，白方空间更大，但王翼也出现了新的空格。
        c5攻击兵链根部d4；攻击根部比直接挤进e5格更容易组织。
        Nf3保护d4并腾出g1格，为之后出象和易位做准备。
        Nc6再次攻击d4，让c兵和马一起向中心施压。
        Be3发展c1象并保护d4；黑方对d4的压力已经增加，出子同时加强中心支撑。
        cxd4交换d4兵，改变白方兵链结构，给黑子打开线路。
        Nxd4回收兵并把马放到中心，黑方不能把这次交换说成白吃一个兵。
        Bc5攻击d4马并发展王翼象；黑方还需考虑易位与c8象的出路。
    """, 4, check(9, "用兵攻击白方兵链根部d4", "c7兵可以走两格挑战中心。", "c7c5", "c5攻击d4。若d4被交换，e5兵失去原先兵链的根部支撑，黑子也更容易获得开放线路。"))
    private val frenchExchange = branch(french, "交换变例：对称出子", 4, "e4d5 e6d5 g1f3 g8f6 f1d3 f8d6 e1g1 e8g8", """
        exd5用白方e兵交换黑方d兵，中心不再关闭。
        exd5回收白兵，双方各留下一个d兵，c8象的斜线也打开了。
        Nf3发展王翼马并保护d4，为白方易位腾出g1格。
        Nf6发展并保护d5，黑方无需强求制造不对称。
        Bd3瞄准h7并为易位腾出f1。
        Bd6瞄准h2并为黑方易位腾出f8。
        白方易位让王离开开放中心，车来到f1；下一阶段发展后翼轻子。
        黑方也易位；接着发展后翼轻子、把车放到开放的e线。
    """, check(5, "用e6兵回收d5，并打开后翼象", "e6兵可以斜吃d5。", "e6d5", "exd5回收交换中的白兵，并腾出e6，让c8象的斜线不再被自己的e兵堵住。"))

    private val sicilian = route("西西里：开放中心与出子", "e2e4 c7c5 g1f3 b8c6 d2d4 c5d4 f3d4 g8f6 b1c3 d7d6 f1e2 e7e6 e1g1 f8e7 c1e3 e8g8", """
        白方e4控制d5和f5，打开f1象与d1后的出路，黑方需要回应中心争夺。
        c5控制d4，从侧面争夺中心，黑方准备以c兵交换白方d兵。
        Nf3发展马并控制d4，之后可以用d4挑战黑方c5兵。
        Nc6发展并控制d4、e5，黑方保持中心压力。
        d4直接打开中心，准备利用白方较快的出子。
        cxd4用c兵交换白方d兵，黑方c线失去自己的c兵。
        Nxd4回收兵，白马进入中心；不是黑方永久多一个兵。
        Nf6发展并攻击e4，白方必须安排保护。
        Nc3保护e4，白方完成两匹马的发展。
        d6控制e5并打开c8象，黑方建立稳固中心。
        Be2发展王翼象并腾出f1格；白方现在可以完成王翼易位。
        e6控制d5并打开f8象，黑方可以接着Be7。
        白方易位安置王，下一步可以发展c1象并把车放到适合的线路。
        Be7为黑方易位腾出f8，先把王安置好。
        Be3发展并保护d4马，白方可能继续安排后与车。
        黑方易位；之后可用车到c线，并留意d5突破是否得到足够支持。
    """, 2, check(5, "用c兵交换白方的中心d兵", "c5兵正好攻击d4。", "c5d4", "cxd4用侧翼c兵交换中心d兵，打开黑方c线；白方Nxd4之后，双方兵数通常仍相等。"))
    private val sicilianAlapin = branch(sicilian, "阿拉平：应对白方c3", 2, "c2c3 g8f6 e4e5 f6d5 d2d4 c5d4 g1f3 b8c6 f1c4 d5b6", """
        c3准备d4，并计划用c兵回吃到中心；b1马暂时不能走c3。
        Nf6发展并攻击e4，利用白方尚未出子的时机。
        e5攻击f6马，黑方必须回应。
        Nd5把马撤到中心；c3兵只控制d4，并没有攻击d5。
        d4挑战c5，白方开始建立中心。
        cxd4交换白方d兵，接下来注意白方c3兵的回吃可能。
        Nf3先出马，白方暂时没有回吃d4兵，黑方不能为保住兵而放弃发展。
        Nc6发展并增加对d4、e5的压力。
        Bc4瞄准f7，白方准备易位。
        Nb6撤开中心马并攻击c4象，迫使白方处理象的落点；随后发展象、安排易位。
    """, check(5, "撤开被e5兵攻击的马，继续占据中心", "c3兵不控制d5，马可以走到d5。", "f6d5", "Nd5避开e5兵的攻击，同时占据中心。白方c3兵控制的是d4，不会直接吃到d5马。"))
    private val queenLondon = branch(queenGambit, "应对伦敦：中心与b2压力", 2, "c1f4 g8f6 e2e3 c7c5 c2c3 b8c6 g1f3 d8b6 d1b3 c5c4", """
        Bf4建立伦敦站位，白方还没有完成王翼发展。
        Nf6发展并控制e4，黑方准备攻击d4。
        e3兵保护d4，并打开f1象通向d3的斜线，准备完成王翼出子。
        c5直接攻击d4，不让白方毫无压力地摆好全部子力。
        c3保护d4，但白方的后翼马暂时失去c3格。
        Nc6发展后翼马并攻击d4，与c5兵一起向白方中心施压。
        Nf3发展并保护d4，双方围绕这个中心格增加子力。
        Qb6沿b线攻击b2，迫使白方处理后翼威胁。
        Qb3挡住b线并提出换后，黑方可以交换，也可以先制造新威胁。
        c4攻击b3后，并让中心暂时关闭；白方可以撤后或Qxb6换后，黑方仍需完成王翼发展。
    """, check(5, "用c兵挑战伦敦的d4支点", "不要只照着对称站位出子，c7兵可以冲击中心。", "c7c5", "c5直接攻击d4，让白方需要考虑中心交换或增加保护；接下来Nc6、Qb6可继续向d4和b2施压。"), identity = 3)

    val all: List<OpeningCourse> = listOf(
        OpeningCourse("italian", "意大利开局", true, "出子时看住e4，尽快完成易位。", "d3保护e4，c3准备d4；王安全后再争夺中心。", "黑方Nf6攻击e4时先确认保护；Bc4瞄准f7不等于可以盲目弃马。", listOf(italian, italianBranch)),
        OpeningCourse("spanish", "西班牙开局", true, "理解对c6马的压力、象的退路与e4保护。", "象退到a4或b3，易位后Re1保护e4，c3准备d4。", "Bxc6后Nxe5不一定赢兵；a6、b5每次赶象都要检查落点。", listOf(spanish, spanishBranch)),
        OpeningCourse("scotch", "苏格兰开局", true, "通过d4打开中心，用已发展的子回收交换。", "Nxd4回收兵，处理黑方对e4或d4的攻击，然后发展象、易位。", "中心打开时王仍在e1会更危险；不要把交换兵当成净赢一个兵。", listOf(scotch, scotchBranch)),
        OpeningCourse("london", "伦敦体系", true, "先把后翼象放到兵链外，再建立稳固中心。", "Bf4、e3、Nf3、Bd3构成常见站位；检查c5与Qb6后再决定c3和易位。", "Bd6会攻击f4象，Qb6会攻击b2；每次先处理具体威胁。", listOf(london, londonBranch)),
        OpeningCourse("queen-gambit", "后翼弃兵", true, "用c4挑战d5，并让出子配合中心压力。", "Nc3、Bg5或Bf4、e3、Nf3发展；若dxc4，常用e3和Bxc4回收。", "对手拿了c4兵时不要急着出后追；黑方c5反击也要处理。", listOf(queenGambit, queenAccepted)),
        OpeningCourse("double-pawn", "双王兵防守", false, "对1.e4走e5，保护中心并防住早出后的直接威胁。", "Nc6护e5，Bc5或Nf6出子，d6与易位安置王，再发展后翼象。", "四步杀需要同时检查e5和f7；不能只赶后而漏掉一个检查。", listOf(italian.copy(checkpoint = check(5, "发展王翼象并瞄准f2", "f8象可以沿e7-d6到c5。", "f8c5", "Bc5发展象、瞄准f2并腾出f8，为之后Nf6和易位做准备。"), identityPlies = 2), earlyQueen)),
        OpeningCourse("caro-kann", "卡罗康防御", false, "用c6支持d5，学会安排后翼象与兵链。", "先考虑把c8象放到f5，再走e6；白方e5时以c5挑战d4。", "白方Ng3或h5会攻击你的象；提前安排退路，而不是机械走e6。", listOf(caro, caroAdvance)),
        OpeningCourse("french", "法兰西防御", false, "理解关闭中心里的兵链与c5反击。", "e6、d5建立支撑；遇到e5，退马后用c5、Nc6共同攻击d4。", "c8象会受e6兵限制，需要后续安排出路；不要让王长期留在打开的中心。", listOf(french, frenchExchange)),
        OpeningCourse("sicilian", "西西里防御", false, "用c5争夺d4，理解开放c线与正常出子。", "交换d4后发展两匹马，d6、e6支持中心，Be7和易位把王安置好。", "白方c3时路线会改变；看到e5攻击马，先撤马再执行其他计划。", listOf(sicilian, sicilianAlapin)),
        OpeningCourse("queen-defense", "后兵稳固防守", false, "对1.d4建立d5支点，分别应对后翼弃兵与伦敦。", "对c4可用e6、Nf6护d5并易位；对伦敦常用c5冲击d4、Qb6攻击b2。", "e6会限制c8象，可考虑b6、Bb7解决；不要只守住d5却一直不出子。", listOf(queenGambit.copy(checkpoint = check(3, "用兵保护受到c4攻击的d5", "e7兵前进一步就能保护d5。", "e7e6", "e6兵支持d5，并打开f8象。代价是c8象暂时被限制，后续要安排出路。"), identityPlies = 4), queenLondon)),
    ).map { it.copy(routes = it.routes + OpeningBranches.forCourse(it)) }

    fun match(game: GameRecord): OpeningMatch? = all.asSequence().filter { it.humanWhite == game.humanWhite }
        .flatMap { course -> course.routes.asSequence().mapIndexedNotNull { index, route ->
            val shared = route.moves.zip(game.moves).takeWhile { (a, b) -> a == b }.size
            if (shared < route.identityPlies) null else OpeningMatch(course, index, shared,
                (shared + 1).takeIf { shared < route.moves.size && shared < game.moves.size })
        } }.maxByOrNull { it.sharedPlies }
}
