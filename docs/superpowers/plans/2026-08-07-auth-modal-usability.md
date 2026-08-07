# Auth Modal Usability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a desktop-draggable title bar to the shared sign-in modal and keep Google and email-link guidance and results inside their respective sections.

**Architecture:** Keep the feature inside the existing shared Thymeleaf template and common stylesheet. The template owns semantic structure, message routing, and dependency-free Pointer Events behavior; the stylesheet owns the title-bar, section, message, drag-state, and mobile rules. Existing Java authentication endpoints and callback codes do not change.

**Tech Stack:** Thymeleaf HTML, browser JavaScript (ES5-compatible style already used by the template), CSS, Java 17, JUnit 5, Maven.

## Global Constraints

- Preserve existing authentication endpoints and query-string result codes.
- Preserve the focus trap, initial focus, focus restoration, Escape close, and backdrop close behaviors.
- Use native Pointer Events; add no dependency.
- Dragging starts only from the title bar with the primary pointer.
- Clamp the card within the viewport using an 8 px edge gap.
- Disable dragging at viewport widths of 480 px or less.
- Reset the translated card position whenever the modal opens or closes.
- Do not persist drag state between openings or page loads.
- Keep the existing dark navy, cyan, and glass-border visual theme.
- Both method-specific message regions use `role="status"`, `aria-live="polite"`, and `aria-atomic="true"`.

---

## File Structure

- `wesite-web/src/main/resources/views/template.html`: modal markup, authentication-result routing, and Pointer Events drag behavior.
- `wesite-web/src/main/resources/static/style/common.css`: title-bar, section, message, dragging, and mobile presentation.
- `wesite-web/src/test/java/info/wesite/web/view/AuthModalTemplateTest.java`: template-level regression tests for structure, routing, accessibility, and drag behavior.

No new production or test files are needed because this repository already verifies shared-template behavior with focused string assertions in `AuthModalTemplateTest`.

### Task 1: Separate the authentication methods and their messages

**Files:**
- Modify: `wesite-web/src/main/resources/views/template.html:221-236,314-353`
- Modify: `wesite-web/src/main/resources/static/style/common.css:36-74`
- Test: `wesite-web/src/test/java/info/wesite/web/view/AuthModalTemplateTest.java`

**Interfaces:**
- Consumes: Existing `setAuthMsg(id, msg, color)`, email request JSON response, and `login` query parameter.
- Produces: DOM regions `googleLoginMsg` and `emailLoginMsg`; method sections `auth-google-section` and `auth-email-section`; routing helper `loginMessageTarget(loginCode)` returning a DOM id string.

- [ ] **Step 1: Replace the generic-message accessibility test with failing method-specific tests**

Add these tests to `AuthModalTemplateTest` and remove `loginResultsAreAnnouncedPolitely()`:

```java
@Test
void authenticationMethodsKeepTheirCopyAndStatusTogether() throws IOException {
    String template = template();

    int googleSection = template.indexOf("class=\"auth-method auth-google-section\"");
    int googleButton = template.indexOf("id=\"googleLoginButton\"");
    int googleMessage = template.indexOf("id=\"googleLoginMsg\"");
    int divider = template.indexOf("class=\"auth-login-divider\"");
    int emailSection = template.indexOf("class=\"auth-method auth-email-section\"");
    int emailCopy = template.indexOf("We'll email you a secure, password-free sign-in link.");
    int emailButton = template.indexOf("id=\"sendEmailLoginButton\"");
    int emailMessage = template.indexOf("id=\"emailLoginMsg\"");

    assertTrue(googleSection < googleButton && googleButton < googleMessage);
    assertTrue(googleMessage < divider && divider < emailSection);
    assertTrue(emailSection < emailCopy && emailCopy < emailButton && emailButton < emailMessage);
    assertTrue(template.contains("id=\"googleLoginMsg\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\""));
    assertTrue(template.contains("id=\"emailLoginMsg\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\""));
}

@Test
void authenticationResultsRouteToTheirOwnMethod() throws IOException {
    String template = template();

    assertTrue(template.contains("setAuthMsg('emailLoginMsg','Please enter your email address.'"));
    assertTrue(template.contains("setAuthMsg('emailLoginMsg',d.msg||'Check your inbox for a sign-in link.'"));
    assertTrue(template.contains("function loginMessageTarget(loginCode)"));
    assertTrue(template.contains("return loginCode==='invalid'?'emailLoginMsg':'googleLoginMsg'"));
    assertTrue(template.contains("setAuthMsg(loginMessageTarget(loginCode),result.message,result.color)"));
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from the repository root:

```powershell
mvn -pl wesite-web -Dtest=AuthModalTemplateTest test
```

Expected: FAIL because `auth-google-section`, `auth-email-section`, `googleLoginMsg`, `emailLoginMsg`, and `loginMessageTarget` do not exist, and email messages still target `loginMsg`.

- [ ] **Step 3: Implement the method-specific markup**

In `template.html`, keep the future title bar outside `loginForm`, and replace the current content inside `loginForm` with this structure:

```html
<div id="loginForm">
    <div class="auth-method auth-google-section" th:if="${_googleLoginEnabled}">
        <a id="googleLoginButton" class="auth-google-button" href="/oauth2/authorization/google">Continue with Google</a>
        <div id="googleLoginMsg" class="auth-method-message" role="status" aria-live="polite" aria-atomic="true"></div>
    </div>
    <div class="auth-login-divider" th:if="${_googleLoginEnabled}"><span>or</span></div>
    <div class="auth-method auth-email-section">
        <p class="auth-method-description">We'll email you a secure, password-free sign-in link.</p>
        <div class="auth-email-field">
            <label for="loginEmail">Email Address</label>
            <input id="loginEmail" type="email" placeholder="you@example.com">
        </div>
        <button id="sendEmailLoginButton" onclick="sendEmailLogin()">Email Me a Sign-in Link</button>
        <div id="emailLoginMsg" class="auth-method-message" role="status" aria-live="polite" aria-atomic="true"></div>
    </div>
</div>
```

Retain existing input and button colors and dimensions by moving their inline declarations into the corresponding CSS selectors in the next step. The Google section's Thymeleaf condition belongs on the wrapper; do not duplicate it on the anchor.

- [ ] **Step 4: Route each result to its method-specific region**

In `sendEmailLogin()`, change every `loginMsg` target to `emailLoginMsg`. Add this helper before `showLoginResult()`:

```javascript
function loginMessageTarget(loginCode) {
    return loginCode==='invalid'?'emailLoginMsg':'googleLoginMsg';
}
```

In `showLoginResult()`, replace the generic target call with:

```javascript
setAuthMsg(loginMessageTarget(loginCode),result.message,result.color);
```

Keep the existing `google_bind_required` button-label update unchanged.

- [ ] **Step 5: Add focused layout and message styles**

Add to the authentication-modal section of `common.css`:

```css
.auth-method-description {
    margin: 0 0 16px;
    color: #8b96a8;
    font-size: 14px;
}
.auth-email-field { margin-bottom: 14px; }
.auth-email-field label {
    display: block;
    margin-bottom: 6px;
    color: #8b96a8;
    font-size: 13px;
}
.auth-email-field input {
    box-sizing: border-box;
    width: 100%;
    padding: 10px 14px;
    border: 1px solid rgba(100,255,218,.2);
    border-radius: var(--radius-sm);
    outline: none;
    background: rgba(255,255,255,.05);
    color: #e2e8f0;
    font-size: 14px;
}
#sendEmailLoginButton {
    width: 100%;
    padding: 12px;
    border: 0;
    border-radius: var(--radius-sm);
    background: #64ffda;
    color: #080c18;
    cursor: pointer;
    font-size: 15px;
    font-weight: 700;
}
.auth-method-message {
    display: none;
    margin-top: 10px;
    font-size: 13px;
    line-height: 1.45;
}
```

- [ ] **Step 6: Run the focused test and verify GREEN**

Run:

```powershell
mvn -pl wesite-web -Dtest=AuthModalTemplateTest test
```

Expected: PASS, including the existing Google conditional rendering and callback-code assertions.

- [ ] **Step 7: Commit the independently working message separation**

```powershell
git add -- wesite-web/src/main/resources/views/template.html wesite-web/src/main/resources/static/style/common.css wesite-web/src/test/java/info/wesite/web/view/AuthModalTemplateTest.java
git commit -m "fix: separate authentication method messages"
```

### Task 2: Add the title bar and constrained desktop dragging

**Files:**
- Modify: `wesite-web/src/main/resources/views/template.html:221-313`
- Modify: `wesite-web/src/main/resources/static/style/common.css:36-110`
- Test: `wesite-web/src/test/java/info/wesite/web/view/AuthModalTemplateTest.java`

**Interfaces:**
- Consumes: Existing `authModal`, `authModalDialog`, `openAuthModal(trigger)`, and `closeAuthModal()` globals.
- Produces: DOM drag handle `authModalTitleBar`; functions `resetAuthModalPosition()`, `clampAuthModalPosition(left, top)`, `startAuthModalDrag(event)`, `moveAuthModal(event)`, and `endAuthModalDrag(event)`.

- [ ] **Step 1: Write failing title-bar and drag-behavior tests**

Add to `AuthModalTemplateTest`:

```java
@Test
void authModalHasADedicatedTitleBar() throws IOException {
    String template = template();

    assertTrue(template.contains("id=\"authModalTitleBar\" class=\"auth-modal-titlebar\""));
    assertTrue(template.contains("<h2 id=\"authModalTitle\">Sign In</h2>"));
    assertTrue(template.contains("class=\"auth-modal-close\""));
}

@Test
void authModalDraggingIsDesktopOnlyConstrainedAndReset() throws IOException {
    String template = template();

    assertTrue(template.contains("window.matchMedia('(max-width: 480px)').matches"));
    assertTrue(template.contains("event.button!==0"));
    assertTrue(template.contains("event.target.closest('button,a,input')"));
    assertTrue(template.contains("authModalTitleBar.setPointerCapture(event.pointerId)"));
    assertTrue(template.contains("Math.max(edgeGap,Math.min(left,window.innerWidth-authModalDialog.offsetWidth-edgeGap))"));
    assertTrue(template.contains("Math.max(edgeGap,Math.min(top,window.innerHeight-authModalDialog.offsetHeight-edgeGap))"));
    assertTrue(template.contains("authModalDialog.style.transform='none'"));
    assertTrue(template.contains("authModalTitleBar.addEventListener('pointerdown',startAuthModalDrag)"));
    assertTrue(template.contains("authModalTitleBar.addEventListener('pointermove',moveAuthModal)"));
    assertTrue(template.contains("authModalTitleBar.addEventListener('pointerup',endAuthModalDrag)"));
    assertTrue(template.contains("authModalTitleBar.addEventListener('pointercancel',endAuthModalDrag)"));
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
mvn -pl wesite-web -Dtest=AuthModalTemplateTest test
```

Expected: FAIL because the title bar and drag functions/listeners do not exist.

- [ ] **Step 3: Add the title-bar markup**

Make the modal card itself free of large inline presentation rules except the dialog attributes. Place this block before `loginForm`:

```html
<div id="authModalTitleBar" class="auth-modal-titlebar">
    <h2 id="authModalTitle">Sign In</h2>
    <button class="auth-modal-close" onclick="closeAuthModal()" aria-label="Close sign-in dialog">&times;</button>
</div>
```

The title bar is not focusable and has no button role; only the close control participates in keyboard navigation.

- [ ] **Step 4: Implement position reset, clamping, and Pointer Events dragging**

After the existing auth-modal globals, add:

```javascript
var authModalTitleBar=document.getElementById('authModalTitleBar');
var authModalDrag=null;
var authModalEdgeGap=8;
function resetAuthModalPosition() {
    authModalDrag=null;
    authModalDialog.style.left='';
    authModalDialog.style.top='';
    authModalDialog.style.transform='none';
}
function clampAuthModalPosition(left,top) {
    var edgeGap=authModalEdgeGap;
    return {
        left:Math.max(edgeGap,Math.min(left,window.innerWidth-authModalDialog.offsetWidth-edgeGap)),
        top:Math.max(edgeGap,Math.min(top,window.innerHeight-authModalDialog.offsetHeight-edgeGap))
    };
}
function startAuthModalDrag(event) {
    if(event.button!==0||event.target.closest('button,a,input')||window.matchMedia('(max-width: 480px)').matches)return;
    var rect=authModalDialog.getBoundingClientRect();
    authModalDrag={pointerId:event.pointerId,x:event.clientX,y:event.clientY,left:rect.left,top:rect.top};
    authModalDialog.style.left=rect.left+'px';
    authModalDialog.style.top=rect.top+'px';
    authModalDialog.classList.add('is-dragging');
    authModalTitleBar.setPointerCapture(event.pointerId);
    event.preventDefault();
}
function moveAuthModal(event) {
    if(!authModalDrag||event.pointerId!==authModalDrag.pointerId)return;
    var next=clampAuthModalPosition(
        authModalDrag.left+event.clientX-authModalDrag.x,
        authModalDrag.top+event.clientY-authModalDrag.y
    );
    authModalDialog.style.left=next.left+'px';
    authModalDialog.style.top=next.top+'px';
}
function endAuthModalDrag(event) {
    if(!authModalDrag||event.pointerId!==authModalDrag.pointerId)return;
    authModalDrag=null;
    authModalDialog.classList.remove('is-dragging');
}
authModalTitleBar.addEventListener('pointerdown',startAuthModalDrag);
authModalTitleBar.addEventListener('pointermove',moveAuthModal);
authModalTitleBar.addEventListener('pointerup',endAuthModalDrag);
authModalTitleBar.addEventListener('pointercancel',endAuthModalDrag);
```

Call `resetAuthModalPosition()` immediately before showing the modal in `openAuthModal()` and immediately before hiding it in `closeAuthModal()`.

- [ ] **Step 5: Style the card, title bar, drag state, and mobile fallback**

Add or consolidate these rules in `common.css`:

```css
.auth-modal-card {
    position: fixed;
    width: calc(100% - 40px);
    max-width: 400px;
    overflow: hidden;
    border: 1px solid rgba(100,255,218,.2);
    border-radius: 16px;
    background: #0d1117;
    box-shadow: var(--shadow-lg);
}
.auth-modal-titlebar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 16px 18px;
    border-bottom: 1px solid var(--glass-border);
    cursor: move;
    cursor: grab;
    touch-action: none;
    user-select: none;
}
.auth-modal-card.is-dragging .auth-modal-titlebar { cursor: grabbing; }
.auth-modal-titlebar h2 {
    margin: 0;
    color: #e2e8f0;
    font-size: 20px;
    line-height: 1.2;
}
.auth-modal-close {
    padding: 2px 5px;
    border: 0;
    background: none;
    color: #8b96a8;
    cursor: pointer;
    font-size: 24px;
    line-height: 1;
}
#loginForm { padding: 24px 28px 28px; }
@media (max-width: 480px) {
    .auth-modal-card { width: calc(100% - 24px); }
    .auth-modal-titlebar { cursor: default; touch-action: auto; }
    #loginForm { padding: 22px; }
}
```

Remove the obsolete `.auth-modal-card` mobile rule that uses `!important` and any conflicting inline card, title, close-button, input, and email-button styles now represented in `common.css`.

- [ ] **Step 6: Run the focused test and verify GREEN**

Run:

```powershell
mvn -pl wesite-web -Dtest=AuthModalTemplateTest test
```

Expected: PASS with all title-bar, drag, accessibility, routing, and existing auth-modal tests green.

- [ ] **Step 7: Run the complete web-module test suite**

Run:

```powershell
mvn -pl wesite-web test
```

Expected: BUILD SUCCESS with no test failures or errors.

- [ ] **Step 8: Inspect the final diff for scope and whitespace errors**

Run:

```powershell
git diff --check
git diff -- wesite-web/src/main/resources/views/template.html wesite-web/src/main/resources/static/style/common.css wesite-web/src/test/java/info/wesite/web/view/AuthModalTemplateTest.java
```

Expected: `git diff --check` produces no output. The visible diff changes only modal markup/styles/script and its focused test.

- [ ] **Step 9: Commit the title bar and drag behavior**

```powershell
git add -- wesite-web/src/main/resources/views/template.html wesite-web/src/main/resources/static/style/common.css wesite-web/src/test/java/info/wesite/web/view/AuthModalTemplateTest.java
git commit -m "feat: make sign-in modal draggable"
```

### Task 3: Browser-level visual and interaction verification

**Files:**
- Verify only: `wesite-web/src/main/resources/views/template.html`
- Verify only: `wesite-web/src/main/resources/static/style/common.css`

**Interfaces:**
- Consumes: The completed modal and the application's existing local run configuration.
- Produces: Verification evidence only; no production interface.

- [ ] **Step 1: Start the web module with its documented local configuration**

Run from `wesite-web`:

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Open `http://localhost:80`. If the required MySQL, Redis, configuration, or secrets are unavailable, record the exact startup failure and rely on the automated suite rather than changing application configuration.

- [ ] **Step 2: Verify desktop interaction at a viewport wider than 480 px**

Open the sign-in dialog and confirm:

- `Sign In` appears in a distinct title bar with the close button on the right.
- The Google message location is immediately below the Google button.
- The password-free explanation appears only in the email section.
- Dragging from the title bar moves the modal.
- Dragging toward each viewport edge leaves the full card reachable with an 8 px gap.
- Dragging on the input, Google link, email button, or close button does not initiate a drag.
- Closing and reopening returns the modal to the center.
- Escape, backdrop close, Tab trapping, and focus restoration still work.

- [ ] **Step 3: Verify mobile interaction at 480 px width**

Confirm the dialog stays centered, dragging the title bar does not move it, content fits without horizontal scrolling, and the email input remains easy to focus and edit.

- [ ] **Step 4: Verify reduced regressions and report results**

Confirm there are no console errors during open, drag, close, Google-result rendering, or email validation. If browser verification required no fixes, do not create an empty commit; report the automated and manual checks with the two implementation commit hashes.
