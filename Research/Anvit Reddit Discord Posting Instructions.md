# Anvit Reddit and Discord Posting Instructions

Use this as a manual posting playbook. Do not paste every template unchanged. Adjust wording for the community, check rules first, and reply to feedback quickly.

## Core App Summary

**Play Store:** https://play.google.com/store/apps/details?id=com.likhith.anvit

Anvit is a private Android AI assistant for chatting with PDF and Word documents fully offline. It runs Gemma 4 locally through Google LiteRT, uses on-device retrieval over your files, and keeps documents, questions, and conversations on the phone.

Lead with these points:

- Chat with PDFs and DOCX files without uploading them.
- Works offline after the model is downloaded.
- No cloud, no tracking, no document collection.
- Answers are grounded in retrieved passages and citations.
- Agentic RAG goes beyond simple search: it routes the query, decomposes complex questions, retrieves with hybrid search, checks relevance, re-queries when weak, reduces context, and self-critiques answers.

Use the Agentic RAG pipeline diagram for technical audiences.

Diagram assets:

- `Research/Agentic RAG Flow Diagram.png`
- `Research/agentic_flow_diagram.png`

Attach one of those diagrams when the audience is likely to care about architecture, retrieval quality, LiteRT, local LLMs, or RAG internals. For general Android or student audiences, prioritize app screenshots or a short demo instead.

## Pre-Post Checklist

- Open the target subreddit or Discord channel rules before posting.
- Confirm self-promotion is allowed or that the post is framed as a technical project / feedback request.
- Use the direct Play Store URL only. Do not use redirect, referral, or shortened links.
- Attach the Agentic RAG flow diagram for technical communities.
- Prefer a 15-45 second demo showing a real document query, ideally with airplane mode visible.
- Keep claims verifiable. If you mention "no tracking" or "no cloud", be ready for privacy-focused users to inspect network behavior.
- Ask for feedback, not upvotes.
- Stay online for the next few hours and answer every serious comment.

## Posting Cadence

Do not post everywhere on the same day.

Recommended order:

1. `r/LocalLLaMA`
2. `r/selfhosted`
3. `r/androidapps`
4. `r/privacy`
5. `r/SideProject`
6. Student/research communities: `r/GetStudying`, `r/GradSchool`, `r/PhD`, `r/AskAcademia`
7. Broader AI communities only after the first feedback round: `r/artificial`, `r/singularity`, `r/OpenAI`, `r/apps`, `r/AlphaAndBetaUsers`

Spacing:

- Leave 3-7 days between major subreddit posts.
- Rewrite the title and body for each community.
- If a post gets strong feedback, fold that feedback into the next post.

## Reddit Templates

### r/LocalLLaMA

**Use when:** You want technical feedback from local LLM users.

**Attach:** Agentic RAG pipeline diagram, plus demo video if available.

**Title option:**

> I built a 100% offline Android app for chatting with PDFs using Gemma 4 via LiteRT

**Post body:**

> Hi everyone, I built Anvit, an Android app for chatting with PDFs and Word documents fully offline.
>
> Play Store: https://play.google.com/store/apps/details?id=com.likhith.anvit
>
> The app runs Gemma 4 locally through Google LiteRT. Documents are indexed on-device, and answers are generated from retrieved passages without sending files or prompts to a server.
>
> The part I wanted feedback on from this community is the RAG pipeline. It uses:
>
> - query routing for direct, single-shot, and agentic paths
> - query decomposition for complex questions
> - hybrid retrieval with dense search + BM25-style keyword matching
> - relevance evaluation before generation
> - re-query / supplemental retrieval when the first retrieval pass is weak
> - context reduction before inference
> - a self-critique pass that can trigger gap retrieval
>
> I attached the pipeline diagram. I would really appreciate feedback on the architecture, retrieval approach, and what benchmarks or phone-specific stats you would want to see next.

**Follow-up comment to add after posting:**

> I am especially interested in feedback from people who already run local models on Android. If you try it, please mention your phone model, which Gemma model you used, and where the UX or retrieval quality breaks.

**Rules / caution:**

- Do not use hype words like "revolutionary" or "best".
- Technical users will ask for tokens/sec, model size, source availability, and privacy proof. Have honest answers ready.
- If you do not have benchmark numbers ready, say that clearly and ask what devices people want benchmarked.

### r/selfhosted

**Current recommendation:** Do not make a standalone Anvit post in `r/selfhosted`.

The subreddit rule says mobile apps are allowed only as companions to a self-hosted backend. Anvit is a standalone local-first Android app, not a companion app for a server the user self-hosts. Posting it as a normal project post is likely off-topic under Rule 1 even though the privacy/no-cloud values overlap.

Only post there if one of these becomes true:

- Anvit adds a companion mode for a self-hosted backend.
- The post is a reply in an allowed megathread where moderators explicitly permit adjacent local-first projects.
- A moderator confirms the project is acceptable despite being standalone mobile.

If you already started writing a post, do not submit it as-is. Use the safer moderator-check comment below or move the launch to `r/privacy`, `r/androidapps`, `r/LocalLLaMA`, or `r/SideProject`.

**Moderator-check message:**

> Hi mods, quick check before I post: I built Anvit, a local-first Android app for offline PDF/DOCX chat where inference and document indexing run on-device. It has no hosted service and no self-hosted backend, so I suspect it may not fit Rule 1 because mobile apps are allowed only as companions to self-hosted backends. Is this acceptable in the weekly project megathread, or should I skip posting it here?

**Use only if moderators allow it:** You want privacy-first and anti-cloud users.

**Attach:** App screenshots or airplane-mode demo first. Diagram is optional.

**Title option:**

> I built an offline Android PDF chat app with no server and no telemetry

**Post body:**

> I built Anvit because I wanted to query private PDFs and Word documents without uploading them to a cloud AI service.
>
> Play Store: https://play.google.com/store/apps/details?id=com.likhith.anvit
>
> It is an Android app that runs Gemma 4 on-device through LiteRT. You import PDFs or DOCX files, the app indexes them locally, and you can ask questions over your documents without a server.
>
> What matters for this community:
>
> - no document upload
> - no account required
> - no cloud inference
> - no tracking
> - works offline after the model is downloaded
>
> It is not self-hosted in the server sense; it is closer to "self-contained on your phone." I would love feedback from people who avoid cloud document tools and want local-first workflows.

**Rules / caution:**

- Be explicit that this is not a server app.
- Do not oversell it as "self-hosted"; frame it as local-first / no server.

**New Project Megathread top-level comment:**

> **Project Name:** Anvit
>
> **Repo/Website Link:** https://play.google.com/store/apps/details?id=com.likhith.anvit
>
> **Description:** Anvit is an Android app for chatting with PDF and Word documents fully offline. I built it for people who want AI help with private documents without uploading those files to a cloud service.
>
> It runs Gemma 4 locally through Google LiteRT. You import PDFs or DOCX files, Anvit indexes them on-device, and then you can ask questions over your documents. The app uses a local agentic RAG pipeline with hybrid retrieval, relevance checks, context reduction, and cited source passages, so answers are grounded in the files on your phone.
>
> Why it may fit this community:
>
> - no server dependency
> - no account required
> - no document upload
> - no cloud inference
> - no tracking
> - works offline after the model is downloaded
>
> This is not self-hosted in the Docker/server sense. It is more of a local-first, self-contained Android app where the phone is the compute environment. I would love feedback from people who care about private, no-cloud document workflows.
>
> **Deployment:** Released on Google Play for Android. Install from the Play Store link above. After installing, download the local model in the app, import a PDF or Word document, and start asking questions. No Docker image or server setup is needed because inference and document indexing run on-device.
>
> **AI Involvement:** The app itself is an AI app. It uses Gemma 4 locally through Google LiteRT for generation, plus on-device retrieval over imported documents. AI was also used during development for code assistance, copy drafting, debugging help, and launch planning.

### r/androidapps

**Use when:** You want Android app users and Play Store installs.

**Attach:** App screenshots or demo. Do not lead with the architecture diagram.

**Title option:**

> Anvit - chat with PDFs and Word docs fully offline on Android

**Post body:**

> I launched Anvit, an Android app that lets you chat with PDF and Word documents offline.
>
> Play Store: https://play.google.com/store/apps/details?id=com.likhith.anvit
>
> It runs a local AI model on your phone, so your documents and questions do not need to be sent to a server. You can use it for textbooks, research papers, reports, contracts, or other documents you want to keep private.
>
> Main features:
>
> - PDF and DOCX document chat
> - offline use after model download
> - no cloud processing
> - no tracking
> - answers grounded in document passages
> - multiple document collections
>
> I would really appreciate Android-specific feedback: install flow, model download, performance, crashes, confusing UI, and whether the app works well on your phone.

**Rules / caution:**

- Use the direct Play Store link.
- Avoid cross-posting the same text into other Android subreddits on the same day.

### r/privacy

**Use when:** You want privacy critique, not broad installs.

**Attach:** Airplane-mode demo if available. Diagram is optional and should be secondary.

**Title option:**

> I built an Android document AI app where PDFs stay on-device

**Post body:**

> I built Anvit for people who want AI help with documents but do not want to upload private PDFs or Word files to cloud services.
>
> Play Store: https://play.google.com/store/apps/details?id=com.likhith.anvit
>
> It runs Gemma 4 locally on Android through LiteRT. Document indexing and question answering happen on the device. The app is designed around a simple privacy promise: your documents, prompts, and conversations stay on your phone.
>
> What it does:
>
> - chat with PDFs and DOCX files
> - retrieve answers from local document passages
> - work offline after the model is downloaded
> - avoid accounts, cloud inference, and tracking
>
> I am looking for privacy-focused feedback. What would you want to inspect before trusting this kind of app? Network behavior? Permissions? Privacy policy wording? Store listing claims?

**Rules / caution:**

- Expect hard questions.
- Do not argue defensively. Treat skepticism as useful review.
- If any claim is not yet independently audited, say so.

### r/SideProject

**Use when:** You want founder/story feedback.

**Attach:** Screenshots or short demo first. Diagram can be linked lower in the post.

**Title option:**

> I launched Anvit, an offline Android AI app for chatting with documents

**Post body:**

> I am a solo developer and just launched Anvit on the Play Store:
>
> https://play.google.com/store/apps/details?id=com.likhith.anvit
>
> The idea came from a simple frustration: I wanted to use AI on PDFs, research papers, and reports without uploading private files to a server.
>
> Anvit runs a local AI model on Android and lets you chat with PDFs and Word documents offline. Under the hood it uses an agentic RAG pipeline: it breaks complex questions into smaller searches, retrieves relevant passages, checks whether the retrieval is good enough, and refines weak answers.
>
> I would love feedback on:
>
> - whether the store listing makes the value clear
> - whether the app onboarding is understandable
> - which user group I should focus on first: students, researchers, professionals, or privacy-conscious users
> - any obvious launch mistakes

**Rules / caution:**

- This audience likes the building story. Keep it human and practical.

### r/MachineLearning

**Use only if:** You have a real technical writeup, benchmarks, or implementation details. Do not post a basic app announcement.

**Attach:** Agentic RAG pipeline diagram and benchmark table.

**Title option:**

> [P] An on-device agentic RAG pipeline for PDF/DOCX question answering on Android

**Post body skeleton:**

> I built a project called Anvit: an Android app for offline PDF/DOCX question answering using local Gemma 4 inference through LiteRT.
>
> Play Store: https://play.google.com/store/apps/details?id=com.likhith.anvit
>
> The technical contribution is the on-device agentic RAG pipeline rather than the UI. The pipeline routes queries into direct, single-shot, or agentic paths; decomposes complex prompts; uses hybrid retrieval; evaluates retrieval relevance; performs re-query or supplemental retrieval; reduces context; and runs a self-critique loop before finalizing answers.
>
> I attached the pipeline diagram. I am looking for feedback on evaluation design, retrieval failure modes, mobile constraints, and what baselines would make this useful to compare against.
>
> Before posting here, add concrete details for:
>
> - current limitations, such as unsupported file types, slow devices, or known retrieval failures
> - benchmark gaps, such as missing tokens/sec numbers or missing comparison baselines
> - device constraints, such as model size, RAM requirements, and tested Android devices

**Rules / caution:**

- Fill in actual benchmark data or concrete implementation details before posting.
- If you cannot add real numbers, skip this subreddit for now.

### Student and Research Communities

Targets: `r/GetStudying`, `r/GradSchool`, `r/PhD`, `r/AskAcademia`.

**Attach:** App screenshots or a student/research use-case demo. Avoid architecture-first framing.

**Title option:**

> I built an offline Android app to chat with research papers and textbooks

**Post body:**

> I built Anvit, an Android app for students and researchers who want to ask questions over PDFs and Word documents without uploading them to a cloud AI tool.
>
> Play Store: https://play.google.com/store/apps/details?id=com.likhith.anvit
>
> It can be used for papers, textbooks, lecture notes, reports, and draft documents. The AI model runs on the phone after download, and document search happens locally.
>
> I am looking for feedback from people who read a lot of PDFs:
>
> - Would this fit your study or research workflow?
> - What document formats matter most?
> - Do citations/source passages feel trustworthy enough?
> - What would make you uninstall it?
>
> Honest criticism is more useful than praise.

**Rules / caution:**

- Check each sub's self-promotion rules carefully.
- Do not imply it replaces academic reading or expert review.

### Broader AI and App Communities

Targets: `r/artificial`, `r/singularity`, `r/OpenAI`, `r/apps`, `r/AlphaAndBetaUsers`.

**Use after:** You have already posted in higher-fit communities and improved the pitch.

**Title option:**

> Anvit: private offline document chat on Android

**Post body:**

> I launched Anvit, an Android app for chatting with PDFs and Word documents using on-device AI.
>
> Play Store: https://play.google.com/store/apps/details?id=com.likhith.anvit
>
> It is built for people who want AI help with documents but do not want to upload those documents to a cloud service. The app runs a local model on your phone, indexes documents locally, and answers from retrieved passages.
>
> I would appreciate feedback on the app, the positioning, and what use cases are most compelling.

**Rules / caution:**

- These are lower-fit communities. Keep expectations modest.
- Do not spam multiple broad AI subs in one day.

## Discord Instructions

General Discord rules:

- Post only in channels that explicitly allow projects, showcases, or self-promotion.
- If a server has `#introductions`, introduce yourself first.
- Keep the first message short. Offer the diagram or details after someone engages.
- Do not mass-post identical text across servers.
- Watch for replies for at least 1-2 hours after posting.

### Hugging Face Discord

**Channels:** `#i-made-this`, `#on-device`

**Message:**

> Hey everyone, I just launched Anvit, an Android app for offline PDF/DOCX chat using Gemma 4 locally through LiteRT.
>
> Play Store: https://play.google.com/store/apps/details?id=com.likhith.anvit
>
> It indexes documents on-device and uses an agentic RAG pipeline for query decomposition, hybrid retrieval, relevance checks, context reduction, and self-critique. No cloud inference or document upload.
>
> I would love feedback from people working on on-device models or retrieval. I can share the pipeline diagram if useful.

**Attach diagram:** Yes, especially in `#on-device`.

### Ollama Discord

**Channel:** `#showcase`

**Message:**

> I launched Anvit, a local-first Android app for chatting with PDFs and Word docs offline.
>
> Play Store: https://play.google.com/store/apps/details?id=com.likhith.anvit
>
> It is not an Ollama client; it runs Gemma 4 locally on Android through LiteRT. The interesting part is local document RAG on a phone: indexing, retrieval, relevance checks, and answer generation all happen on-device.
>
> Would appreciate feedback from local model users on the UX, positioning, and what device/performance details you would want to see.

**Attach diagram:** Optional. Share after someone asks for architecture details.

### Nous Research Discord

**Channel:** `#projects`

**Message:**

> Sharing a small Android project I launched: Anvit, an offline AI document assistant for PDFs and DOCX files.
>
> Play Store: https://play.google.com/store/apps/details?id=com.likhith.anvit
>
> It runs Gemma 4 on-device with LiteRT and uses an agentic RAG pipeline for multi-step retrieval and answer refinement. The goal is private document QA without accounts, cloud inference, or document upload.
>
> I would appreciate technical feedback on the retrieval flow and what evals would make this more credible.

**Attach diagram:** Yes.

### LM Studio Discord

**Channel:** `#community-showcase`

**Message:**

> I launched Anvit, an Android app for local document chat.
>
> Play Store: https://play.google.com/store/apps/details?id=com.likhith.anvit
>
> LM Studio makes local AI approachable on desktop; I am trying to make private document chat approachable on Android. Anvit runs Gemma 4 locally through LiteRT and does RAG over PDFs/DOCX files on-device.
>
> Feedback welcome, especially from people who already use local models and care about privacy or document workflows.

**Attach diagram:** Optional.

### Open WebUI Discord

**Channel:** `#showcase`

**Message:**

> I built Anvit, an Android app for private offline document chat.
>
> Play Store: https://play.google.com/store/apps/details?id=com.likhith.anvit
>
> It is a standalone mobile app rather than a web UI. Documents are indexed locally, Gemma 4 runs on-device through LiteRT, and answers are grounded in local PDF/DOCX passages.
>
> Would love feedback from people who use local AI workflows: what would you expect from a mobile document RAG app?

**Attach diagram:** Optional.

### EleutherAI Discord

**Recommended approach:** Do not pitch immediately.

First message in a relevant discussion:

> I am working on an Android on-device RAG app for PDF/DOCX QA using local Gemma inference. I am trying to think through evaluation for retrieval quality under mobile constraints. Are there lightweight eval setups people here recommend for document QA / RAG failure modes?

Only share Anvit after someone asks for the project.

**If asked for the link:**

> The app is Anvit: https://play.google.com/store/apps/details?id=com.likhith.anvit
>
> The pipeline uses query routing, decomposition, hybrid retrieval, relevance evaluation, context reduction, and a self-critique/gap retrieval loop. Happy to share the diagram if useful.

## Feedback Reply Templates

### If someone asks whether documents leave the phone

> Documents and prompts are intended to stay on-device. The app runs local inference after the model is downloaded and does document indexing locally. If you inspect network behavior and find anything unexpected, please tell me; privacy feedback is one of the main reasons I am posting.

### If someone asks why not use a cloud assistant

> Cloud assistants are more powerful, but I built Anvit for cases where the document is private, sensitive, or just not worth uploading. It trades raw model power for local control and privacy.

### If someone asks why this is different from a chat-only local LLM app

> The main difference is document RAG. Anvit indexes PDFs/DOCX files locally and retrieves passages before answering, so the model is grounded in your files instead of just chatting from its base knowledge.

### If someone asks why this is different from AnythingLLM / PocketPal / MLC Chat

> Those projects are useful and I do not see Anvit as a replacement for all of them. The focus here is a simple Android-first app for offline PDF/DOCX RAG with citations, no server setup, and no cloud account.

### If someone asks for source code

> It is not fully open-source right now. I understand that matters for privacy-focused users. I am prioritizing launch feedback first, and I am open to sharing more technical details, network behavior, and implementation notes.

### If someone reports a bug

> Thanks, that is useful. Could you share your phone model, Android version, which model you downloaded, and what document type triggered it? A screenshot or exact error text would help a lot.

## What Not To Say

Avoid:

- "Best offline AI app"
- "Completely secure"
- "Guaranteed private"
- "Replaces ChatGPT"
- "Production-grade RAG"
- "Please upvote"
- "Please support my launch"

Use instead:

- "I built this and would appreciate feedback."
- "Documents are intended to stay on-device."
- "The app is early, and I am looking for real-world failure cases."
- "I would like feedback on privacy, retrieval quality, and Android performance."

## Suggested First Week Manual Schedule

Day 1:

- Post in `r/LocalLLaMA`.
- Attach the Agentic RAG diagram and, if available, a short demo.
- Reply to all comments.

Day 2:

- Post in Hugging Face Discord `#on-device` or `#i-made-this`.
- Ask specifically for on-device / retrieval feedback.

Day 3:

- Do not post a new Reddit thread. Reply to existing comments and collect issues.

Day 4:

- Post in `r/selfhosted` with privacy-first framing.

Day 5:

- Post in LM Studio or Ollama Discord showcase channel.

Day 6:

- Post in `r/androidapps` with app-user framing.

Day 7:

- Review feedback and rewrite the next batch of posts before posting to broader or student communities.
