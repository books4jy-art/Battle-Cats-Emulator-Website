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
    battleTitle: "2 · Battle simulation",
    colc: "Collection",
    map: "Map",
    stage: "Stage",
    autoHint: "The first five basic cats are deployed automatically whenever they can be.",
    run: "Run battle",
    instant: "Instant",
    time: "Time",
    money: "Money",
    enemyBase: "Enemy base",
    catBase: "Cat base",
    win: "Victory! The enemy base was destroyed.",
    lose: "Defeat. The cat base was destroyed.",
    running: "Battle in progress…",
    timeUp: "Stopped after 10 minutes of battle time.",
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
    battleTitle: "2 · 전투 시뮬레이션",
    colc: "분류",
    map: "맵",
    stage: "스테이지",
    autoHint: "기본 캐릭터 5마리를 출격할 수 있을 때마다 자동으로 출격해요.",
    run: "전투 시작",
    instant: "즉시",
    time: "시간",
    money: "돈",
    enemyBase: "적 성",
    catBase: "냥코 성",
    win: "승리! 적 성을 무너뜨렸어요.",
    lose: "패배. 냥코 성이 무너졌어요.",
    running: "전투 중…",
    timeUp: "전투 시간 10분이 지나서 멈췄어요.",
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
