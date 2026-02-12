const elements = {
  chatThread: document.querySelector("#chatThread"),
  composer: document.querySelector("#composer"),
  questionInput: document.querySelector("#questionInput"),
  newChatButton: document.querySelector("#newChatButton"),
  profileLevel: document.querySelector("#profileLevel"),
  profileTopics: document.querySelector("#profileTopics"),
  profileBehavior: document.querySelector("#profileBehavior"),
  detailDrawer: document.querySelector("#detailDrawer"),
  drawerBackdrop: document.querySelector("#drawerBackdrop"),
  closeDrawerButton: document.querySelector("#closeDrawerButton"),
  detailOriginal: document.querySelector("#detailOriginal"),
  detailHistory: document.querySelector("#detailHistory"),
  toast: document.querySelector("#toast")
};

const topicRules = [
  { topic: "函数", keywords: ["函数", "顶点", "最值", "导数", "单调"] },
  { topic: "几何", keywords: ["几何", "三角形", "圆", "向量", "角度"] },
  { topic: "概率", keywords: ["概率", "随机", "独立", "期望", "方差"] },
  { topic: "物理", keywords: ["力", "加速度", "电场", "磁场", "电流"] },
  { topic: "化学", keywords: ["氧化", "还原", "反应", "离子", "平衡"] }
];

const state = {
  idSeed: 0,
  historySeed: 0,
  messages: [],
  spans: new Map(),
  activeDrawerSpanId: null,
  profile: {
    level: "高二 · 进阶冲刺",
    topicHits: new Map(),
    followups: 0,
    voiceFollowups: 0
  }
};

let toastTimer = null;

boot();

function boot() {
  bindGlobalEvents();
  seedConversation();
  renderProfile();
  renderThread();
}

function bindGlobalEvents() {
  elements.composer.addEventListener("submit", handleSendQuestion);
  elements.newChatButton.addEventListener("click", resetConversation);
  elements.closeDrawerButton.addEventListener("click", closeDrawer);
  elements.drawerBackdrop.addEventListener("click", closeDrawer);
  window.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      closeDrawer();
    }
  });
}

function seedConversation() {
  state.messages = [];
  state.spans.clear();
  state.activeDrawerSpanId = null;
  state.profile.topicHits.clear();
  state.profile.followups = 0;
  state.profile.voiceFollowups = 0;

  const intro = [
    "你好，我是你的学习搭子。这个界面看起来是普通 AI Chat，但每一段都能左滑右滑交互。",
    "左滑松手会自动讲解当前段落，不会把讲解追加到底部，而是存进段落详解记录。",
    "左滑后不松手会进入语音追问模式，松手即提交追问并更新你的学习画像。",
    "右滑可拉出详解弹窗，回看该段在当时生成的讲解内容。"
  ].join("\n\n");

  pushAssistantMessage(intro, "初始化引导");
}

function handleSendQuestion(event) {
  event.preventDefault();
  const question = elements.questionInput.value.trim();
  if (!question) {
    return;
  }

  pushUserMessage(question);
  updateProfile(question, { isFollowup: false, isVoice: false });
  const reply = buildTutorReply(question);
  pushAssistantMessage(reply, question);

  elements.questionInput.value = "";
  renderProfile();
  renderThread();
}

function resetConversation() {
  seedConversation();
  closeDrawer();
  renderProfile();
  renderThread();
  showToast("已开始新对话");
}

function pushUserMessage(text) {
  state.messages.push({
    id: createId("msg"),
    role: "user",
    text,
    time: createTimeLabel()
  });
}

function pushAssistantMessage(text, sourceQuestion) {
  const spans = splitParagraphs(text).map((content) => {
    const span = {
      id: createId("span"),
      content,
      sourceQuestion,
      history: []
    };
    state.spans.set(span.id, span);
    return span;
  });

  state.messages.push({
    id: createId("msg"),
    role: "assistant",
    spans,
    time: createTimeLabel()
  });
}

function buildTutorReply(question) {
  const topics = detectTopics(question);
  const focus = topics[0] || "这个知识点";
  const second = topics[1] ? `并且串联 ${topics[1]}` : "并且避免审题漏条件";

  return [
    `先给你结论：${focus}题最稳的做法，是把题干翻译成“已知 -> 目标 -> 校验”三步，再下手计算。`,
    `做题时建议你先写一个 20 秒框架：先列已知量和限制，再写要证什么，最后检查单位或区间，${second}。`,
    "如果某段看不懂，直接左滑该段：松手会自动讲解；左滑不松手会进入语音追问；右滑则呼出该段详解历史。"
  ].join("\n\n");
}

function splitParagraphs(text) {
  const byBlankLine = text
    .split(/\n{2,}/)
    .map((line) => line.trim())
    .filter(Boolean);

  if (byBlankLine.length > 1) {
    return byBlankLine;
  }

  const sentences = text.match(/[^。！？!?]+[。！？!?]?/g) || [text];
  const chunks = [];
  let current = "";

  sentences.forEach((sentence) => {
    if ((current + sentence).length > 54 && current) {
      chunks.push(current.trim());
      current = sentence;
      return;
    }

    current += sentence;
  });

  if (current.trim()) {
    chunks.push(current.trim());
  }

  return chunks;
}

function renderThread() {
  elements.chatThread.innerHTML = "";

  state.messages.forEach((message) => {
    const row = document.createElement("article");
    row.className = `message-row message-row--${message.role}`;

    const bubble = document.createElement("div");
    bubble.className = `bubble bubble--${message.role}`;

    if (message.role === "user") {
      const paragraph = document.createElement("p");
      paragraph.className = "user-text";
      paragraph.textContent = message.text;
      bubble.append(paragraph);
    }

    if (message.role === "assistant") {
      message.spans.forEach((span) => {
        const paragraph = document.createElement("section");
        paragraph.className = "assistant-paragraph";
        paragraph.dataset.spanId = span.id;

        const text = document.createElement("p");
        text.className = "assistant-text";
        text.textContent = span.content;

        const wave = document.createElement("p");
        wave.className = "recording-wave";
        wave.textContent = "录音中... 松手后提交语音追问";

        const meta = document.createElement("div");
        meta.className = "paragraph-meta";

        const hint = document.createElement("span");
        hint.className = "hint-pill";
        hint.textContent = "左滑讲解 / 长按语音 / 右滑详解";

        const detailCounter = document.createElement("span");
        detailCounter.className = "detail-counter";
        detailCounter.textContent = `详解 ${span.history.length} 条`;

        meta.append(hint, detailCounter);
        paragraph.append(text, wave, meta);
        bindSpanGesture(paragraph, span.id);
        bubble.append(paragraph);
      });
    }

    row.append(bubble);
    elements.chatThread.append(row);
  });

  elements.chatThread.scrollTop = elements.chatThread.scrollHeight;
}

function bindSpanGesture(target, spanId) {
  const drag = {
    active: false,
    pointerId: null,
    startX: 0,
    deltaX: 0,
    holdTimer: null,
    recordingMode: false
  };

  const clearTimer = () => {
    if (drag.holdTimer) {
      window.clearTimeout(drag.holdTimer);
      drag.holdTimer = null;
    }
  };

  const resetCard = () => {
    clearTimer();
    drag.active = false;
    drag.pointerId = null;
    drag.deltaX = 0;
    drag.recordingMode = false;
    target.style.transform = "translateX(0px)";
    target.classList.remove("dragging", "recording");
  };

  target.addEventListener("pointerdown", (event) => {
    if (event.pointerType === "mouse" && event.button !== 0) {
      return;
    }

    drag.active = true;
    drag.pointerId = event.pointerId;
    drag.startX = event.clientX;
    drag.deltaX = 0;
    drag.recordingMode = false;

    target.classList.add("dragging");
    target.setPointerCapture(event.pointerId);
  });

  target.addEventListener("pointermove", (event) => {
    if (!drag.active || event.pointerId !== drag.pointerId) {
      return;
    }

    const rawDelta = event.clientX - drag.startX;
    drag.deltaX = clamp(rawDelta, -130, 96);
    target.style.transform = `translateX(${drag.deltaX}px)`;

    if (drag.deltaX < -24 && !drag.holdTimer && !drag.recordingMode) {
      drag.holdTimer = window.setTimeout(() => {
        drag.recordingMode = true;
        target.classList.add("recording");
        showToast("继续按住在录音，松手后提交追问");
      }, 620);
    }

    if (drag.deltaX > -14 && !drag.recordingMode) {
      clearTimer();
    }
  });

  target.addEventListener("pointerup", (event) => {
    if (!drag.active || event.pointerId !== drag.pointerId) {
      return;
    }

    const swipeDistance = drag.deltaX;
    const isRecording = drag.recordingMode;
    resetCard();

    if (isRecording) {
      submitVoiceFollowup(spanId);
      return;
    }

    if (swipeDistance <= -72) {
      explainSpan(spanId);
      return;
    }

    if (swipeDistance >= 72) {
      openDrawer(spanId);
    }
  });

  target.addEventListener("pointercancel", resetCard);
}

function explainSpan(spanId) {
  const span = state.spans.get(spanId);
  if (!span) {
    return;
  }

  const explanation = {
    id: createHistoryId(),
    mode: "自动讲解",
    time: createDateTimeLabel(),
    sourceQuestion: span.sourceQuestion,
    text: buildSpanExplanation(span.content)
  };

  span.history.unshift(explanation);
  renderThread();
  showToast("已生成该段讲解，右滑即可查看详解弹窗");
}

function submitVoiceFollowup(spanId) {
  const span = state.spans.get(spanId);
  if (!span) {
    return;
  }

  const voiceQuestion = `语音追问：这段“${shorten(span.content, 22)}”我没懂，能换个角度讲吗？`;
  pushUserMessage(voiceQuestion);
  updateProfile(voiceQuestion, { isFollowup: true, isVoice: true });

  const answer = [
    `好的，换一个更口语的说法：${buildSpanExplanation(span.content)}`,
    "你可以先把关键词圈出来，再把这一段转换成“已知条件 + 动作 + 结果”，这样下一次同类题会更稳。"
  ].join("\n\n");

  pushAssistantMessage(answer, voiceQuestion);

  span.history.unshift({
    id: createHistoryId(),
    mode: "语音追问",
    time: createDateTimeLabel(),
    sourceQuestion: voiceQuestion,
    text: answer
  });

  renderProfile();
  renderThread();
  showToast("语音追问已提交，并更新学习画像");
}

function buildSpanExplanation(content) {
  const clean = content.replace(/[。！？!?]+$/, "");
  return `把这句拆成两步就容易了：先确认题目给了什么条件，再确认你要推出什么结论。${clean}。做题时先写“已知/求证”两行，准确率会明显提升。`;
}

function openDrawer(spanId) {
  const span = state.spans.get(spanId);
  if (!span) {
    return;
  }

  state.activeDrawerSpanId = spanId;
  elements.detailOriginal.textContent = span.content;
  elements.detailHistory.innerHTML = "";

  if (span.history.length === 0) {
    const empty = document.createElement("p");
    empty.className = "empty-history";
    empty.textContent = "这段还没有讲解记录。先左滑松手自动讲解，再右滑查看。";
    elements.detailHistory.append(empty);
  } else {
    span.history.forEach((item) => {
      const card = document.createElement("article");
      card.className = "history-card";

      const meta = document.createElement("p");
      meta.className = "history-meta";
      meta.textContent = `${item.mode} · ${item.time}`;

      const text = document.createElement("p");
      text.className = "history-text";
      text.textContent = item.text;

      card.append(meta, text);
      elements.detailHistory.append(card);
    });
  }

  elements.drawerBackdrop.hidden = false;
  elements.detailDrawer.classList.add("open");
  elements.detailDrawer.setAttribute("aria-hidden", "false");
}

function closeDrawer() {
  state.activeDrawerSpanId = null;
  elements.detailDrawer.classList.remove("open");
  elements.detailDrawer.setAttribute("aria-hidden", "true");
  elements.drawerBackdrop.hidden = true;
}

function renderProfile() {
  elements.profileLevel.textContent = `画像等级：${state.profile.level}`;

  const sortedTopics = Array.from(state.profile.topicHits.entries())
    .sort((left, right) => right[1] - left[1])
    .slice(0, 3)
    .map((entry) => `${entry[0]}(${entry[1]})`);

  elements.profileTopics.textContent = `关注知识点：${sortedTopics.join("、") || "暂无"}`;
  elements.profileBehavior.textContent = `追问：${state.profile.followups} 次 · 语音追问：${state.profile.voiceFollowups} 次`;
}

function updateProfile(text, options) {
  const topics = detectTopics(text);
  topics.forEach((topic) => {
    const current = state.profile.topicHits.get(topic) || 0;
    state.profile.topicHits.set(topic, current + 1);
  });

  if (options.isFollowup) {
    state.profile.followups += 1;
  }

  if (options.isVoice) {
    state.profile.voiceFollowups += 1;
  }
}

function detectTopics(text) {
  const normalized = text.toLowerCase();
  const hits = [];

  topicRules.forEach((rule) => {
    const hasMatch = rule.keywords.some((keyword) => normalized.includes(keyword));
    if (hasMatch) {
      hits.push(rule.topic);
    }
  });

  if (hits.length === 0) {
    hits.push("通用方法");
  }

  return hits;
}

function showToast(text) {
  elements.toast.textContent = text;
  elements.toast.classList.add("show");

  if (toastTimer) {
    window.clearTimeout(toastTimer);
  }

  toastTimer = window.setTimeout(() => {
    elements.toast.classList.remove("show");
  }, 1600);
}

function createId(prefix) {
  state.idSeed += 1;
  return `${prefix}-${state.idSeed}`;
}

function createHistoryId() {
  state.historySeed += 1;
  return `history-${state.historySeed}`;
}

function createTimeLabel() {
  return new Date().toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit"
  });
}

function createDateTimeLabel() {
  return new Date().toLocaleString("zh-CN", {
    hour12: false,
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  });
}

function shorten(text, maxLength) {
  if (text.length <= maxLength) {
    return text;
  }

  return `${text.slice(0, maxLength)}...`;
}

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max);
}
