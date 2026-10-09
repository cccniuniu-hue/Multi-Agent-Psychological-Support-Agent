const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../../main/resources/static/app.js"), "utf8");
const bindings = source.indexOf("\nels.loginForm.addEventListener");
assert.ok(bindings > 0);

function harness(request) {
  const elements = new Map();
  const context = vm.createContext({
    request,
    document: {
      querySelector(selector) {
        if (!elements.has(selector)) {
          elements.set(selector, {
            innerHTML: "existing conversation",
            value: "hello",
            disabled: false,
            focus() {},
            classList: { toggle() {} }
          });
        }
        return elements.get(selector);
      }
    }
  });
  // Load the real functions without page startup, authentication, or network requests.
  vm.runInContext(source.slice(0, bindings), context);
  vm.runInContext(`
    api = request;
    showEmpty = () => {};
    loadUserMemories = async () => {};
    state.sessionId = "session/1";
    globalThis.subject = { state, els, startNewSession, sendMessage };
  `, context);
  return context.subject;
}

test("new conversation waits for successful end and blocks concurrent chat or end requests", async () => {
  let release;
  let requests = 0;
  const subject = harness((url, options) => {
    requests++;
    assert.equal(url, "/api/chat/sessions/session%2F1/end");
    assert.equal(options.method, "POST");
    return new Promise(resolve => { release = resolve; });
  });
  const pending = subject.startNewSession();
  assert.equal(subject.state.sessionId, "session/1");
  assert.equal(subject.els.messages.innerHTML, "existing conversation");
  assert.equal(subject.els.sendButton.disabled, true);
  assert.equal(subject.els.switchAccount.disabled, true);
  await subject.startNewSession();
  await subject.sendMessage({ preventDefault() {} });
  assert.equal(requests, 1);

  release();
  await pending;
  assert.equal(subject.state.sessionId, null);
  assert.equal(subject.els.messages.innerHTML, "");
  assert.equal(subject.state.sending, false);
  assert.equal(subject.els.sendButton.disabled, false);
});

test("failed end preserves the current conversation and permits retry", async () => {
  const subject = harness(async () => { throw new Error("offline"); });
  await subject.startNewSession();
  assert.equal(subject.state.sessionId, "session/1");
  assert.equal(subject.els.messages.innerHTML, "existing conversation");
  assert.match(subject.els.sessionBadge.textContent, /失败/);
  assert.equal(subject.state.sending, false);
  assert.equal(subject.els.newSessionButton.disabled, false);
});

test("empty conversations do not send an end request", async () => {
  const subject = harness(async () => { assert.fail("unexpected end request"); });
  subject.state.sessionId = null;
  await subject.startNewSession();
  assert.equal(subject.state.sessionId, null);
  assert.equal(subject.els.messages.innerHTML, "");
});

test("admin accounts cannot end student conversations", async () => {
  const subject = harness(async () => { assert.fail("unexpected end request"); });
  subject.state.isAdmin = true;
  await subject.startNewSession();
  assert.equal(subject.state.sessionId, "session/1");
});
