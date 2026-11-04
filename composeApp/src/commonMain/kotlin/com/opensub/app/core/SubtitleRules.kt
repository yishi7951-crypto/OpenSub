package com.opensub.app.core

/**
 * The translation rule set. Each rule was tried on its example with Hy-MT2 1.25-bit, with and without it, and kept only where
 * the plain translation was wrong and the rule put it right (145 tried on 2026-11-03, 37 kept; 3 more from the user's SpaceX
 * talk recording the same day). Names too, where people go by a Chinese name the model does not know (Jensen Huang came out as
 * 黄金森; the recognizer's "Jensen Wang" is him as well) or the recognizer writes them in small letters ("condi rice" was 康迪米);
 * of 20 well-known names tried, the model already had most, and ignored the reference for Lisa Su and Morris Chang. Rules the model already got right were left out: every rule a request matches makes it about
 * 0.1 s slower. So were rules that did not help ("stand
 * someone up" stayed 让我站了起来), and rules that are nearly a whole sentence: given "hit the nail on the head translates to
 * 说到点子上", the model answered "You hit the nail on the head" with that phrase alone. Phrases often meant literally are
 * narrowed ("things went south", not "went south") or left out ("blow it" in "blow it up").
 */
val SUBTITLE_RULES = TranslationRules("""
    {drive} someone away = 把某人逼走 # I drove her away.
    closed book = 让人看不透的人 # He's a closed book.
    {wing} it = 凭感觉来 # We're just winging it.
    {pull} someone's leg = 跟某人开玩笑 # She's pulling your leg.
    break a leg = 祝你好运 # Break a leg tonight!
    {spill} the beans = 说漏嘴 # Who spilled the beans?
    arm and a leg = 一大笔钱 # That car cost an arm and a leg.
    once in a blue moon = 很少 # We only see each other once in a blue moon.
    {kick} the bucket = 死了 # The old man finally kicked the bucket.
    {get} the hang of = 掌握窍门 # You'll get the hang of it.
    my bad = 我的错 # My bad, I forgot to call you.
    no-brainer = 显而易见的选择 # Honestly, it's a no-brainer.
    game changer = 颠覆性的东西 # This feature is a game changer.
    the elephant in the room = 大家都避而不谈的问题 # Let's talk about the elephant in the room.
    {touch} base = 联系一下 # Let's touch base next week.
    low-hanging fruit = 最容易做的事 # Let's start with the low-hanging fruit.
    {move} the needle = 带来明显改变 # These small changes don't really move the needle.
    {learn} the ropes = 摸清门道 # It takes a while to learn the ropes.
    out of the blue = 突然 # He called me out of the blue.
    {play} it by ear = 见机行事 # Let's just play it by ear.
    {throw} someone under the bus = 出卖某人 # He threw me under the bus.
    when pigs fly = 等太阳从西边出来 # He'll apologize when pigs fly.
    cold feet = 临阵退缩 # He got cold feet before the wedding.
    rain check = 改天吧 # Can I take a rain check?
    {sleep} on it = 考虑一晚再说 # Let me sleep on it.
    {keep} it down = 小声点 # Keep it down, the baby's asleep.
    {knock} it off = 别闹了 # Knock it off, you two.
    {cut} it out = 别闹了 # Cut it out, I'm serious.
    sanity check = 简单核对 # Let's do a quick sanity check.
    speak of the devil = 说曹操曹操到 # Speak of the devil, here he comes.
    things {go} south = 情况变糟 # Things went south pretty quickly.
    {nail} it = 做得很好 # You totally nailed it.
    {crush} it = 干得漂亮 # You crushed it out there.
    {bomb} the test = 考砸了 # I totally bombed the test.
    {ace} the test = 考得很好 # She aced the test.
    {bail} on = 放鸽子 # Sorry I bailed on you last night.
    ghosted someone = 突然不理某人 # She just ghosted me.
    coding agents = 编程智能体 # Coding agents are getting better.
    on the ball = 机灵能干 # like on the ball,
    vibe code = 凭感觉让 AI 写代码 # and barely understand what I'm doing and you know vibe code my way
    {ship} something PRs = 提交 PR # I got to ship a hundred PRs this week
    jensen huang = 黄仁勋 # Jensen Huang, the founding CEO of NVIDIA,
    jensen wang = 黄仁勋 # my conversation with Jensen Wang
    jensen wong = 黄仁勋 # my conversation with Jensen Wong
    condi rice = 康迪·赖斯 # The amazing condi rice.
    yann lecun = 杨立昆 # Yann LeCun runs AI research at Meta.
    light wells = 采光井 # And that's why there are light wells on the roof.
    light well = 采光井 # But when you have a light well in the roof,
""".trimIndent())
