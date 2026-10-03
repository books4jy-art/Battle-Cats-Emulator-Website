// UI text in English and Korean. Elements with data-t="key" get their text from here.
"use strict";

const TEXT = {
  en: {
    brand: "Battle Cats Emulator",
    title: 'Battle Cats Ultimate, <span class="accent">in your browser</span>',
    lede: "BCU's battle engine compiled to JavaScript. Game data is downloaded from BCU's own servers the first time and kept in your browser.",
    dataTitle: "1 · Game data",
    load: "Load game data",
    loading: "Loading…",
    loaded: "Loaded",
    lineupTitle: "2 · Lineup",
    lineupHint: "Tap a slot to choose a cat, its form and level. The second row is optional (swipe up/down in battle to switch rows).",
    battleTitle: "3 · Battle",
    colc: "Collection",
    map: "Map",
    stage: "Stage",
    run: "Start battle",
    restart: "Restart battle",
    auto: "Auto-deploy the front row",
    pause: "Pause",
    resume: "Resume",
    full: "Full screen",
    exitFull: "Exit full screen",
    rotate: "Turn your phone sideways for a bigger battle.",
    controls: "Tap a cat to deploy it · bottom left: worker level-up · bottom right: cat cannon · drag to scroll · pinch or mouse wheel to zoom · swipe up/down on the lineup to switch rows. Keys: 1–5 deploy, Q worker, E cannon, R switch row, P pause.",
    paused: "Paused",
    close: "Close",
    empty: "Empty",
    search: "Search cats by name or number",
    pickTitle: (n) => `Slot ${n}`,
    level: "Level",
    plus: "Plus",
    remove: "Remove",
    form: (n) => ["Normal", "Evolved", "True", "Ultra"][n] || `Form ${n + 1}`,
    inLineup: "Already in the lineup",
    emptyLineup: "Put at least one cat in the lineup first.",
    time: "Time",
    money: "Money",
    enemyBase: "Enemy base",
    catBase: "Cat base",
    win: "Victory! The enemy base was destroyed.",
    lose: "Defeat. The cat base was destroyed.",
    running: "Battle in progress…",
    noCanvas: "This browser can't draw the battle (no OffscreenCanvas). Please update it, or try a recent Chrome, Edge, Firefox or Safari.",
    timeUp: "Time's up.",
    foot: "Private test build. Battle Cats © PONOS. Battle Cats Ultimate by the BCU team.",
    stIndex: "Downloading the file list…",
    stData: (mb, total) => `Downloading game data… ${mb} / ${total} MB`,
    stCore: "Starting the game engine…",
    stDone: (s) => `Ready: ${s.units} cats, ${s.enemies} enemies. ${s.mb} MB downloaded (${s.cached} parts from cache) in ${s.sec} s.`,
  },
  kr: {
    brand: "냥코 대전쟁 에뮬레이터",
    title: '배틀 캣츠 얼티밋을 <span class="accent">브라우저에서</span>',
    lede: "BCU의 전투 엔진을 자바스크립트로 옮겼어요. 게임 데이터는 처음 한 번 BCU 서버에서 받아 브라우저에 저장해요.",
    dataTitle: "1 · 게임 데이터",
    load: "게임 데이터 불러오기",
    loading: "불러오는 중…",
    loaded: "불러옴",
    lineupTitle: "2 · 편성",
    lineupHint: "칸을 눌러 캐릭터, 형태, 레벨을 골라요. 두 번째 줄은 선택 사항이에요 (전투 중 위/아래로 밀어서 줄을 바꿔요).",
    battleTitle: "3 · 전투",
    colc: "분류",
    map: "맵",
    stage: "스테이지",
    run: "전투 시작",
    restart: "다시 시작",
    auto: "앞줄 자동 출격",
    pause: "일시정지",
    resume: "계속",
    full: "전체 화면",
    exitFull: "전체 화면 끝내기",
    rotate: "휴대폰을 가로로 돌리면 전투 화면이 커져요.",
    controls: "캐릭터를 눌러 출격 · 왼쪽 아래: 일꾼 레벨업 · 오른쪽 아래: 냥코 대포 · 끌어서 이동 · 두 손가락이나 마우스 휠로 확대/축소 · 편성 칸을 위/아래로 밀어 줄 바꾸기. 키보드: 1–5 출격, Q 일꾼, E 대포, R 줄 바꾸기, P 일시정지.",
    paused: "일시정지됨",
    close: "닫기",
    empty: "비어 있음",
    search: "이름이나 번호로 캐릭터 검색",
    pickTitle: (n) => `${n}번 칸`,
    level: "레벨",
    plus: "플러스",
    remove: "빼기",
    form: (n) => ["기본", "진화", "제3형태", "제4형태"][n] || `형태 ${n + 1}`,
    inLineup: "이미 편성에 있어요",
    emptyLineup: "먼저 편성에 캐릭터를 한 마리 이상 넣어 주세요.",
    time: "시간",
    money: "돈",
    enemyBase: "적 성",
    catBase: "냥코 성",
    win: "승리! 적 성을 무너뜨렸어요.",
    lose: "패배. 냥코 성이 무너졌어요.",
    running: "전투 중…",
    noCanvas: "이 브라우저에서는 전투 화면을 그릴 수 없어요 (OffscreenCanvas 미지원). 브라우저를 업데이트하거나 최신 Chrome, Edge, Firefox, Safari를 사용해 주세요.",
    timeUp: "시간이 다 됐어요.",
    foot: "비공개 테스트 버전. 냥코 대전쟁 © PONOS. 배틀 캣츠 얼티밋은 BCU 팀이 만들었어요.",
    stIndex: "파일 목록을 받는 중…",
    stData: (mb, total) => `게임 데이터를 받는 중… ${mb} / ${total} MB`,
    stCore: "게임 엔진을 시작하는 중…",
    stDone: (s) => `준비 완료: 캐릭터 ${s.units}종, 적 ${s.enemies}종. ${s.mb} MB 받음 (캐시 ${s.cached}개 사용), ${s.sec}초 걸림.`,
  },
};

const BROWSER_LANG = (navigator.language || "").toLowerCase().startsWith("ko") ? "kr" : "en";
let LANG = BROWSER_LANG;
try { LANG = localStorage.getItem("lang") || BROWSER_LANG; } catch (e) { /* storage blocked: use the browser's language */ }

function t(key, ...args) {
  const v = TEXT[LANG][key] ?? TEXT.en[key] ?? key;
  return typeof v === "function" ? v(...args) : v;
}

function applyLang() {
  document.documentElement.lang = LANG === "kr" ? "ko" : "en";
  document.querySelectorAll("[data-t]").forEach((el) => { el.innerHTML = t(el.dataset.t); });
  document.querySelectorAll("[data-lang]").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.lang === LANG)));
  document.title = t("brand");
}
