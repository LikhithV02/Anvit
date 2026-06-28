# Anvit Launch Playbook: A 12-Channel User Acquisition Strategy for a Solo Indie Dev with a Small Budget

**TL;DR**
- Lead with **r/LocalLLaMA (~726K members)** and a **Show HN** modeled on the "Off Grid" launch (124 points, Feb 2026) — these two channels alone are where your highest-intent users (privacy-conscious tinkerers running local models on phones) congregate, and both are free.
- Spend nothing on paid ads for the first 30 days. Use the small budget later on **Reddit Promoted Posts ($50–$150/day test) in r/LocalLLaMA-adjacent communities** rather than Google UAC, because Reddit reaches the exact audience and Google UAC will burn through budget against giant ChatGPT-clone competitors.
- Engage — don't pitch — three specific people who can amplify on-device launches: **@ggerganov (llama.cpp creator)**, **@ghorbani_asghar / @pocketpal_ai (PocketPal AI)**, and **@simonw (Simon Willison)**. A single retweet from any of them is worth more than a week of paid ads.

---

## Key Findings

1. **The "local LLM on Android" niche has clear watering holes.** r/LocalLLaMA, Hugging Face Discord (218,186 members), Ollama Discord (196,980), and LM Studio Discord (79,782) collectively contain virtually every person who would download Anvit on Day 1.
2. **Anvit's strongest differentiator is RAG + offline + Gemma 4 + LiteRT on a phone.** Competing apps PocketPal AI, MLC Chat, SmolChat, Maid, Layla and AnythingLLM Mobile mostly do chat-only or sync-required RAG; on-device RAG with citations on a phone is still a thin field.
3. **Show HN is the single best one-shot launch channel.** A neutral, technical title with a GitHub or APK link is the format that wins; the most relevant precedent — "Show HN: Off Grid – Run AI text, image gen, vision offline on your phone" — hit 124 points and 66 comments in Feb 2026 (HN item 47019133).
4. **LinkedIn is a low-yield channel for a B2C indie AI app**; treat it as a personal-brand/credibility play, not a download driver.
5. **In-app feedback via a single Google Form** beats every SaaS feedback SDK at this stage — your first 200 users will give you better insights than any survey tool.
6. **Reddit ads at $50–$150/day in subreddit-targeted campaigns** are the best paid lever; X ads and Google UAC are not.

---

## Details

### 1. Reddit — your highest-leverage organic channel

**Primary subreddits (post in roughly this order, spaced 3–7 days apart, never the same post twice):**

| Subreddit | Members | Why it fits | Best post format |
|---|---|---|---|
| r/LocalLLaMA | ~726,000 | Core audience: people running models locally | Demo video + technical writeup |
| r/SideProject | Indie-builder forgiveness for rough edges | "I built X, here's what I learned" | Story + screenshot |
| r/androidapps | Android-native users | Show-and-tell with screenshots |
| r/selfhosted | ~350,000 | Anti-cloud, pro-local ethos perfectly aligned | Lead with "no cloud, no telemetry, runs offline" |
| r/privacy | Privacy-first messaging | Position as a privacy win, not an AI feature dump |
| r/MachineLearning | Strict — only post if you have a technical contribution (latency benchmarks, RAG-chunking writeup) | [P] (Project) tag, technical depth |
| r/artificial, r/singularity, r/OpenAI | Broader AI audiences; lower fit, use sparingly |
| r/GetStudying, r/GradSchool, r/PhD, r/AskAcademia | Student/researcher use-case framing |
| r/DataHoarder | Tangential but receptive to offline-everything tools |
| r/apps | General app launches |
| r/AlphaAndBetaUsers | Early-tester recruiting |

**Rules to avoid being banned (these are not optional):**
- r/LocalLLaMA's stated guidance: keep self-promotion at or below 10% of your content, use direct links, no sensationalized titles. Build karma on the sub for 1–2 weeks before posting your own app: comment thoughtfully on 10–15 posts.
- r/selfhosted's guidance: "Self-promotion is allowed in context. Lead with value, not your product." Include APK/source link, mention no telemetry up front, answer every comment within hours.
- r/MachineLearning: only post with a [P] tag and only if you have a real technical contribution. A "look at my chat app" post will be removed.
- r/androidapps: requires direct Play Store link, no affiliate/referral URLs, no clickbait.
- **Universal rule:** check each subreddit's rules tab and recent removed posts in modlogs (use Reveddit) before posting.

**Post formats that work (ranked by historical performance in this niche):**
1. **Short demo video (15–45s)** showing a real PDF being loaded and queried offline with airplane mode visibly on. Airplane-mode shots are the single highest-converting visual proof point.
2. **Technical writeup**: "How I got Gemma 4 (2B/4B) running on Android with LiteRT + local vector DB for RAG — tokens/sec, memory, what broke." r/LocalLLaMA rewards depth.
3. **Show-and-tell with a real use case**: "I built this because I didn't want to upload my medical records / legal docs / thesis drafts to OpenAI."
4. **Comparison post** (only after you've launched and have data): Anvit vs. PocketPal AI vs. AnythingLLM Mobile on the same PDF.

**Title templates:**
- "[r/LocalLLaMA] I built a 100% offline Android app for chatting with PDFs using Gemma 4 via LiteRT — no cloud, source/APK below"
- "[r/selfhosted] On-device PDF RAG on Android (Gemma 4 + LiteRT). No server. No telemetry. Feedback welcome."
- "[r/androidapps] Anvit — chat with PDFs/Word docs fully offline (Gemma 4, on-device RAG)"

**Engagement reality check:** r/LocalLLaMA's most-upvoted post in the past 24 hours typically sits around 482 upvotes; a top-of-day post drives roughly 5,000–15,000 outbound clicks. Best posting time is 03:00 UTC (catches US evening + EU morning).

### 2. X / Twitter — engage three people, then ten, then the algorithm

**Tier 1 — must engage (reply thoughtfully to their posts for 2 weeks before mentioning Anvit):**
- **@ggerganov (Georgi Gerganov)** — creator of llama.cpp. Has publicly boosted PocketPal AI: *"The PocketPal AI mobile app (iOS and Android) was open sourced yesterday … Shoutout to the author @ghorbani_asghar"* (Oct 21, 2024). One retweet = thousands of installs.
- **@ghorbani_asghar / @pocketpal_ai** — PocketPal AI creator. Direct adjacent product; engage cooperatively, not competitively. Their latest release notes already mention "New model support via llama.cpp b8827: Gemma 4" — Anvit's RAG-on-device angle is complementary, not competitive.
- **@simonw (Simon Willison)** — runs the most-read local-LLM blog (`simonwillison.net`); Andrej Karpathy himself publicly subscribes: *"Really excellent LLM blog, I sub & read everything."* If Simon writes about Anvit, you've won the week.

**Tier 2 — worth tagging on launch tweet:**
- **@awnihannun (Awni Hannun, Apple MLX lead)** — iOS-leaning but very vocal about on-device. Famous quote: *"If you want to really feel the future, take your iPhone out of its case and run a Deep Seek 7B reasoning model on it"* (Jan 2025).
- **@reach_vb (Vaibhav "VB" Srivastav, ~43.8K followers)** — recently moved from Hugging Face to OpenAI ("Bringing Codex to developers @OpenAI | ex @huggingface") but still posts heavily on open-source/on-device ML.
- **@maximelabonne (Maxime Labonne)** — Head of Post-Training at Liquid AI, Google Developer Expert in AI/ML; Liquid AI's LFM2 is edge-tuned, so the audience overlaps.
- **@osanseviero (Omar Sanseviero) / @HuggingFace** — amplifies mobile/optimum-ExecuTorch content; Gemma + LiteRT posts often flow through these accounts.
- **@LMStudioAI** — official LM Studio account; spiritual sibling on desktop, frequently boosts community tools.
- **@karpathy (Andrej Karpathy)** — only worth tagging on a milestone or a deep technical thread; he's responsive to high-signal posts.

**Hashtags that actually get used in this niche (1–2 per tweet max — Sprout Social's 2025 data shows more than two hashtags causes a 17% drop in engagement):**
- `#LocalLLM`, `#OnDeviceAI`, `#EdgeAI`, `#PrivacyTech`, `#OpenSourceAI`, `#Gemma`, `#LiteRT`, `#Android`, `#RAG`. Skip generic `#AI` and `#MachineLearning` — they're overrun.

**Content that goes viral in this niche:**
1. **Airplane-mode demo videos** — visual proof of offline operation.
2. **Side-by-side comparisons** with cloud assistants on a sensitive document (e.g., a medical report) where the cloud version refuses or leaks.
3. **Token/sec benchmarks** on specific phones (Pixel 9 Pro, Galaxy S25 Ultra, OnePlus 13). The local-AI community lives for these — for reference, MLC Chat hits ~40 tok/sec on Qwen3 1.7B on the S25 Ultra via Hexagon NPU; PocketPal AI hits ~16 tok/sec on Phi-4 Mini on the same hardware.
4. **"I deleted ChatGPT and replaced it with…"** narrative tweets.
5. **Technical threads** explaining how RAG works on-device with a small vector DB.

**Posting cadence:** 2–3 tweets/day; one demo, one technical, one engagement reply on a Tier-1 account.

### 3. LinkedIn — credibility, not downloads

LinkedIn drives roughly 1/100th the install volume of Reddit for a B2C indie app, but it builds the founder credibility that matters when a journalist or recruiter Googles you later. Treat it that way.

**What to do:**
- Post 2–3x/week as the founder, not as a company page. Refine Labs' published data study of 7 employee personal profiles vs. the Refine Labs company page found: *"Despite having an average follower count 46% lower than the Refine Labs company page, the employees averaged more than 2.75x the impressions and 5x the engagement per post."*
- Use **native document carousels (PDFs uploaded directly to LinkedIn)** — Socialinsider's 2026 LinkedIn Benchmarks Report (analyzing 1.3 million posts from 16,645 business pages, Jan 2024–Dec 2025) confirmed *"native document posts…generate the highest levels of engagement out of all LinkedIn content types,"* with multi-image carousels averaging a 6.60% engagement rate.
- Best formats: "lessons learned" posts, technical breakdowns, screenshots of users' reactions. Originality.ai's study of 2,726 LinkedIn long-form posts (Dec 2022–Oct 2024) found *"the average likely-AI-generated post received 45% less engagement than a Likely Original post"* — write it yourself.
- **Don't post in LinkedIn groups** — they're nearly dead.
- **Specifically target student/researcher visibility** by joining 5–10 LinkedIn conversations per week on posts from professors in AI/HCI/privacy departments — comment substantively, then connect.

**Template post (LinkedIn, carousel slide 1):**
> "I built an Android app that lets you chat with your PDFs without ever sending them to a server. Here's why I think on-device AI matters for students, lawyers, and doctors — and what I learned shipping Gemma 4 to a phone."

### 4. Hacker News — the single highest-leverage one-shot

**Your direct precedent:** "Show HN: Off Grid – Run AI text, image gen, vision offline on your phone" — **124 points, 66 comments**, Feb 2026 (HN item 47019133). The author's pitch is the exact template you should mimic:
> "Your phone has a GPU more powerful than most 2018 laptops. Right now it sits idle while you pay monthly subscriptions to run AI on someone else's server, sending your conversations, your photos, your voice to companies whose privacy policy you've never read. Off Grid is an open-source app that puts that hardware to work … llama.cpp for text (15-30 tok/s, any GGUF model), Stable Diffusion for images … Whisper for voice, SmolVLM/Qwen3-VL for vision. Hardware-accelerated on both Android (QNN, OpenCL) and iOS (Core ML, ANE, Metal). MIT licensed."

Other useful precedents: "Show HN: I made an app to use local AI as daily driver" (RecurseChat, HN 39532367); "Show HN: EchoStream – A Local AI Agent That Lives on Your iPhone" (HN 44318655); "Show HN: Plock: Use a local LLM from anywhere in your OS" (HN 39083843).

**Title formula:** `Show HN: Anvit – Offline on-device PDF/Word chat on Android with Gemma 4`
- No exclamation points, no superlatives, no marketing language. HN moderators rewrite these.
- Lead with the noun (Anvit), then a flat description.

**Timing:** Post Tuesday or Wednesday, **8–10 AM US Eastern**. Be physically at your computer for the next 6 hours to reply to every comment within minutes — HN comment velocity is the single biggest ranking signal after vote velocity.

**First comment (post as soon as the Show HN goes live):**
> "Hi HN, Anvit author here. I built this because I wanted to use AI on legal/medical/research PDFs without sending them to OpenAI or Google. It runs Gemma 4 2B/4B via Google's LiteRT-LM on Android, ships a small embedding model + local vector DB for RAG, and has zero network calls (verifiable: pull the APK, run mitmproxy, you'll see nothing). Happy to answer anything about LiteRT, RAG chunking on mobile, tokens/sec on specific phones, or what's still broken."

**Things to do before posting:**
- Make sure Play Store listing loads fast and shows a real demo video at the top.
- Have a GitHub link ready even if it's only for issues — HN punishes closed-source mobile apps but tolerates them if you're upfront.
- Pre-write answers to the 3 questions you'll definitely get: (a) "Why not just use llama.cpp directly?" (b) "What's the tokens/sec on a Pixel?" (c) "How big is the model download?"
- Do NOT ask anyone to upvote. HN's voting-ring detection is excellent and will penalize you.

### 5. Product Hunt — only after you've fixed onboarding

Launch on Product Hunt **30+ days after Show HN**, not before. Reasons: PH traffic converts poorly without polished onboarding, and PH success requires a pre-launch list of 400+ supporters (products with 400+ waitlist subscribers are 3–5x more likely to reach the top 5 per Waitlister's analysis of 100+ launches).

**12-week prep boiled down for a solo dev:**
1. **Now:** Create a Coming Soon page on Product Hunt; PH emails followers on launch day.
2. **Weeks 1–3:** Comment substantively on 30+ Product Hunt launches in adjacent categories (Productivity, AI, Developer Tools). This is what triggers PH algorithmic legitimacy.
3. **Weeks 3–6:** Build a Twitter teaser thread series; collect emails of interested supporters.
4. **Week 6+:** Find a top-500 hunter to launch you. Posts by top-500 hunters average **3.2× more upvotes** (Product Hunt 2023 internal data via Flowjam). Use OpenHunter.ai or DM hunters who hunt AI products.
5. **Launch day:** Post at 12:01 AM PST; target 150–200 upvotes in the first 4 hours; reply to every comment within 5 minutes.

**Required assets:**
- Tagline ≤60 chars: "Chat with PDFs offline. 100% on-device AI for Android."
- Product name ≤40 chars; short description ≤260 chars.
- 30-second GIF demo (60 FPS, <5MB) at top of gallery.
- 1–2 minute YouTube demo video with subtitles.
- Gallery: before/after workflows, a "no internet" screenshot, screenshot of model picker (Gemma 2B vs 4B).

### 6. YouTube & video creators worth pitching

These channels have actually reviewed local-AI tools before. Send a personal email (not a press release) with a 60-second Loom showing the app in action.

- **Matt Wolfe (@mreflow)** — 694,000+ subscribers as of 2025 per Edelman's *AI Creators You Need to Know*; broad AI coverage; covers tools that are easy to demo on camera.
- **Matthew Berman** — **531,000 subscribers as of May 2026** per ThoughtLeaders channel analytics (gaining ~16K/month per vidIQ); open-source / local AI focus; has covered PrivateGPT-style apps (his "How To Install PrivateGPT – Chat With PDF TXT and CSV Files Privately!" video has 412K views — Anvit is the mobile sequel to that exact pitch).
- **Wes Roth** — 313K+ subscribers; AI news; less hands-on but high reach.
- **All About AI, AI Andy, AI Explained, Yannic Kilcher** — mid-size channels with faster reply rates and more aligned audiences.
- **Niche on-device AI YouTubers:** search "local LLM Android" and "Gemma on phone" — channels in the 5K–50K range have the highest reply rates and the most aligned audiences.
- **Tech YouTubers in the privacy space:** Naomi Brockwell (NBTV), The Hated One, Techlore — pitch the privacy angle, not the AI angle.

**Outreach template (email or YouTube DM):**
> Subject: 30-sec demo: Gemma 4 running offline on Android with PDF RAG
> Hi [Name], I'm a solo dev who just shipped Anvit — an Android app that runs Gemma 4 (2B/4B) fully on-device via Google's LiteRT and does RAG on PDFs/Word docs with no internet. Here's a 30-second airplane-mode demo: [Loom link]. Happy to send a press kit, APK, or jump on a call if it's useful for a video. Play Store: [link]. — [Your name]

### 7. Discord & Telegram — high signal, low effort

Join these specific servers (member counts verified May 2026 from each server's public invite landing page), introduce yourself in `#introductions` or `#showcase` channels, and **only share Anvit in channels that explicitly allow self-promotion**:

| Server | Members | Invite | Channel to target |
|---|---|---|---|
| Hugging Face | **218,186** | discord.com/invite/hugging-face-879548962464493619 | `#i-made-this`, `#on-device` |
| Ollama | **196,980** | discord.com/invite/ollama | `#showcase` |
| Nous Research | **106,643** | discord.com/invite/nousresearch | `#projects` |
| LM Studio | **79,782** | discord.com/invite/lmstudio | `#community-showcase` |
| EleutherAI | **35,171** | discord.com/invite/eleutherai | (researcher-heavy; engage first, don't pitch) |
| Open WebUI | **34,138** | discord.com/invite/5rJgQTnV4s | `#showcase` |

**Privacy Guides** (for the privacy-conscious-professional persona) runs on **Matrix + Discourse forum (discuss.privacyguides.net)**, not Discord — by design. Their official guidance explicitly discourages Discord: *"Lack of privacy for private communications … private conversations are not only unencrypted, but also actively scanned."* There's already an active PocketPal AI thread on their forum; reply there with Anvit as a complementary option (cite verifiable network-free behavior).

**Telegram:** there is no large on-device-LLM Telegram channel comparable to the Discords above. Skip Telegram unless you find a niche student or research group.

### 8. Cold outreach — tools and templates that work for $0

**Lead-gen tools with usable free tiers:**

- **Apollo.io Free Forever** — 100 emails/month (personal domain) or up to 10,000/month (corporate domain) under Fair Use, 5 mobile credits, 10 export credits, 2 active sequences, Chrome extension on LinkedIn. Per Apollo: *"Free accounts using a corporate domain are capped at 10,000 email credits per month. If you signed up with a personal Gmail or non-corporate domain, that cap drops to just 100 email credits per month."*
- **Hunter.io Free** — 25 search credits + 50 verification credits/month, *"Your Free Plan renews automatically every month"* (per help.hunter.io). Campaigns up to 500 recipients with 1 mailbox + Chrome extension + CSV export (capped at 10 emails/domain).
- **PhantomBuster** — 14-day free trial (no card): 2h execution, 5 phantom slots, 1,000 AI credits, 50 email credits; then 30 min/month free tier. *"Result file limits: → Each results file is capped at 10 rows."* Useful for scraping commenters from on-device-AI tweets.
- **Clay** — pay-only-when-data-is-found enrichment; pair with Apollo for accuracy. Realistic use case: take a list of arXiv RAG-paper authors and enrich with current emails.
- **arXiv author emails (free, primary source)** — cs.CL and cs.IR papers list affiliation emails on page 1. Pull last 30 days of "retrieval-augmented" or "on-device" papers for 1:1 outreach.
- **University CS department directories** — most publish grad-student rosters with emails at `<university>.edu/people` or `/grad-students`.
- **ResearchGate / Google Scholar + Hunter** — find researchers by topic, then use Hunter to guess email pattern.
- **Wiza** — free trial gives ~20 LinkedIn Sales-Nav-style email reveals; useful only for tiny one-off batches.

**Outreach message template (researcher/professor):**
> Subject: Offline PDF RAG on Android for [their specific research area]
> Hi Prof. [Name], I read your 2025 paper on [specific topic] and noticed you reference [their relevant point about privacy/local compute]. I built a free Android app (Anvit) that runs Gemma 4 locally with on-device RAG over PDFs — no data leaves the phone. Thought it might be useful for [field-specific use case, e.g., "annotating sensitive interview transcripts in the field"]. Would love 2 minutes of feedback if you ever try it: [Play Store link]. — [Your name]

**Outreach message template (student via LinkedIn):**
> Hi [Name], I built an Android app that lets you chat with your textbooks/papers fully offline — no ChatGPT subscription, no data uploaded. Looking for 5 students to try it and tell me what's broken. Free, no signup, takes 2 minutes: [link]. Would love your honest feedback.

**Volume guidance:** 30–50 personalized messages per day is sustainable solo. Reply rate on truly personalized outreach is 15–25%; on templated, 1–3%.

### 9. Google Play ASO — quick wins

Google Play ranks on the **full description** (no separate keyword field) and on **install velocity, retention, and ratings**. Optimize these:

**Title (≤30 chars):** `Anvit: Offline AI Chat for PDFs`
- Includes the primary keyword "Offline AI" and the use case "PDF" up front.

**Short description (≤80 chars):** `Chat with PDFs & Word docs 100% offline. Private on-device AI. No cloud.`

**Long description keywords to weave in naturally** (validate against Google Play autocomplete):
- "offline AI", "chat with PDF", "AI without internet", "private AI assistant", "on-device AI", "local LLM", "Gemma", "AI for students", "AI for research", "PDF summarizer offline", "document AI offline", "private ChatGPT alternative".

**Screenshots (8 total, in this order):**
1. Hero: phone in airplane mode + chat working.
2. PDF loaded with citation-style answer.
3. Word doc support.
4. Model picker (Gemma 2B vs 4B) — signals depth.
5. "Your data never leaves your device" with a visual lock icon.
6. Speed (tokens/sec) on a real phone.
7. Use case 1: student summarizing a textbook.
8. Use case 2: lawyer/doctor (without identifying detail) querying a private doc.

**A/B test** the icon and the first screenshot using Play Console's "Store Listing Experiments" — these two assets account for the majority of install-conversion variance.

**Ask for ratings only after 3 successful interactions** — Google's in-app review API is the right path; never use generic web review prompts.

### 10. Paid ads with a small budget

**Spend nothing for the first 30 days.** Use the time to build the organic flywheel. Once you have data (≥500 installs and at least one viral organic post), allocate budget in this order:

1. **Reddit Promoted Posts ($50–$150/day, 5–10 day test):** target r/LocalLLaMA, r/selfhosted, r/privacy, r/androidapps directly. Reddit minimum daily budget is $5; effective testing range is $50–$150/day. Expected CPC: $0.20–$4.00; CPI: $1–$3 in niche subreddits. Use the Reddit Ad Library to research competitor creatives for free. Critical: install the Reddit Pixel before you start to track conversions (28-day attribution).
2. **X Ads — small-budget engagement campaigns (~$10–20/day):** only to amplify your single best organic tweet, not for installs. Target followers of @ggerganov, @ghorbani_asghar, @simonw, @reach_vb.
3. **Do NOT use Google UAC at this stage.** UAC is a black box that needs $1,000+ to optimize and will cost $3–8 per install against Anvit's ChatGPT-clone competitors. Revisit only after 10,000 organic installs.
4. **Skip Meta/Facebook ads** — wrong audience for privacy-conscious local-AI users.

### 11. Feedback collection — keep it embarrassingly simple

**For your first 1,000 users, the right answer is a single Google Form linked from a "Send Feedback" item in the app's settings menu.** Tools like Alchemer Mobile, Survicate, and Pendo are overkill at this stage and will cost more than your ad budget.

**Structure for the form (max 7 questions, all optional except #1):**
1. What were you trying to do with Anvit today? *(open text)*
2. Did it work? *(yes / partly / no)*
3. What broke or surprised you? *(open text)*
4. Which model did you use? *(Gemma 2B / 4B)*
5. Phone model? *(open text)*
6. How likely are you to recommend Anvit to a friend? *(0–10 NPS)*
7. Email if you want a reply *(optional)*

**Alongside the form, run a Discord server.** Even 50 users in a Discord generates more actionable feedback than 5,000 form responses. Use channels: `#feedback`, `#bugs`, `#feature-requests`, `#show-and-tell`. Reply to every message in week one personally.

**When you outgrow Google Forms (~5K+ users):** switch to **PostHog (open-source, self-hostable, free up to 1M events/month)** — surveys + product analytics + session replay in one tool, which is exactly what an indie dev with a privacy-positioned product should be running.

### 12. Competitors and where their users hang out

| App | Distribution | Where users hang out | What Anvit can do better |
|---|---|---|---|
| **PocketPal AI** | Play Store, App Store, F-Droid; 500K+ downloads, 4.4★/7,424 ratings; ~16 tok/sec on S25 Ultra | r/LocalLLaMA, Privacy Guides forum, GitHub issues | Chat-only, no RAG. Anvit's on-device PDF/Word RAG with citations is a clear differentiator. |
| **MLC Chat** | GitHub releases, Play Store | r/LocalLLaMA, MLC Discord | Speed leader (~40 tok/sec via Hexagon NPU on S25 Ultra) but curated model list, no RAG. |
| **AnythingLLM Mobile** | Play Store (Android; iOS planned) | AnythingLLM Discord, r/LocalLLaMA | Has on-device RAG with citations, but tied to a sync ecosystem; Anvit is simpler and 100% standalone. |
| **SmolChat** | Play Store | r/LocalLLaMA | Any GGUF model, chat-only. |
| **Maid** | F-Droid (no Play Store) | r/LocalLLaMA, F-Droid forums | Open-source purist pick; reaches the GrapheneOS/CalyxOS crowd. |
| **Layla** | Play Store | Layla Discord | Beginner-friendly, curated models. |
| **Google AI Edge Gallery** | GitHub APK sideload | Google developer channels | Demo app, not a product. Anvit can win on UX and being on the Play Store. |
| **Fullmoon** | App Store only (iOS) | r/LocalLLaMA | iOS-only — Anvit owns Android positioning. |

**Where their users actually hang out (concentrated):**
- r/LocalLLaMA (~726,000 members) — the single biggest watering hole; nearly every competitor has been posted there.
- Privacy Guides forum (discuss.privacyguides.net) — has an active PocketPal AI thread you should reply on.
- Hugging Face Discord `#on-device` channel.
- The respective project GitHub Issues — read these to find unmet user needs you can solve.

**Reviewer/blog targets** (for emailed pitches with APK):
- *Android Police* — has reviewed AnythingLLM Mobile favorably ("The private NotebookLM alternative I'm moving all my notes to").
- *It's FOSS* — has covered on-device LLM apps on Android.
- *9to5Google, Android Authority, XDA Developers* — Android-native outlets.
- *The Decoder, MarkTechPost* — AI-focused blogs that cover smaller launches.

---

## Recommendations (sequenced, with thresholds)

**Week 0–1 (this week): Build organic foundation, no ads.**
- Polish Play Store listing with the 8 screenshots above and an airplane-mode demo video.
- Record 3 short demo videos (15s, 30s, 60s) for different platforms.
- Set up a Google Form for feedback and link it from the app.
- Spin up a free Discord server.
- Create Show HN draft + first comment.
- Comment 20 times on r/LocalLLaMA, 10 times on r/selfhosted (don't pitch yet).

**Week 2: First two launches.**
- Day 1: Post on r/LocalLLaMA in the morning UTC (03:00–04:00 UTC catches US evening + EU morning).
- Day 2–3: Reply to every single comment. Iterate based on feedback.
- Day 4: If r/LocalLLaMA went well, post on r/selfhosted with adjusted framing.
- Day 5–7: Tweet thread on X tagging @ghorbani_asghar (cooperatively) and one of the Tier-2 influencers.

**Week 3: Show HN.**
- Tuesday or Wednesday, 8–10 AM ET.
- Have 6 uninterrupted hours after posting.
- **Benchmark to hit: 50+ points and 30+ comments in first 4 hours = front page.**

**Week 4–6: Press, more subreddits, Discord communities.**
- Pitch Android Police, It's FOSS, The Decoder.
- Post in Hugging Face, Ollama, Nous Research, LM Studio Discords.
- Begin cold outreach to 30 researchers/day via arXiv.
- **Benchmark: 5,000 installs total = ready to consider paid.**

**Week 7–10: Paid amplification.**
- Run $50/day Reddit Promoted Posts in r/LocalLLaMA-adjacent communities for 7 days.
- **Threshold to scale to $150/day:** CPI under $3 and Day-7 retention >25%.
- **Threshold to kill:** CPI over $5 or Day-1 retention under 30%.

**Week 12: Product Hunt launch.**
- Only if you have ≥400 email subscribers/Discord members ready to support.
- Find a top-500 hunter.
- **Benchmark: Top 5 of the day = a successful PH launch.**

**Benchmarks that change the strategy:**
- **If r/LocalLLaMA flops (<50 upvotes):** the issue is likely the demo, not the channel. Re-record with airplane-mode visibly enabled and try r/selfhosted instead.
- **If Show HN doesn't make front page:** don't repost; pivot to YouTube creator outreach.
- **If installs plateau under 1,000 by Week 6:** the problem is product-market fit, not marketing. Stop acquisition spend; do 20 user interviews from your Discord.
- **If retention is strong (D7 >30%) but acquisition is slow:** pour money into Reddit ads.
- **If retention is weak:** kill all paid spend; ship two product iterations before resuming.

---

## Caveats

- **Influencer engagement is high-variance.** Tagging @ggerganov does not guarantee a retweet; treat any amplification as a bonus, not a plan.
- **Reddit ban risk is real.** Each subreddit has different rules; one mistimed post can get you shadowbanned site-wide. Always check rules and recent removals.
- **Show HN posts often die without a clear reason.** The Hacker News algorithm is not transparent. Have a backup plan (Lobste.rs, r/programming) ready.
- **Discord member counts above are as of May 2026.** Verify on the day you post.
- **Vaibhav Srivastav (@reach_vb) recently moved from Hugging Face to OpenAI** — his on-device content remains relevant but he is no longer HF's official voice.
- **Cold-outreach laws differ by jurisdiction** (GDPR, CAN-SPAM). Unsolicited bulk email to .edu addresses is legally gray in the EU; personalize and keep volumes low.
- **Gemma 4 in the brief should be verified** — Google's recent on-device push has involved Gemma 3n and Gemma 4 E2B/E4B; ensure your app's marketing copy matches the model variant you're actually shipping. Note that on supported Android devices, Gemma 4 is available system-wide through Android AICore as Gemini Nano, which Google currently calls "the recommended path for production applications" — be ready to explain why a standalone LiteRT-LM integration is the right choice for Anvit.
- **Anvit's privacy claim ("no tracking, no data collection") must be verifiable.** Privacy-aware Redditors will run mitmproxy on your APK. If even one analytics SDK leaks, your launch dies. Audit before posting.