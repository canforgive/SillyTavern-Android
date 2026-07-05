const STORAGE_KEY = 'sillytavern_url';
const AUTH_ENABLED_KEY = 'sillytavern_auth_enabled';
const AUTH_USER_KEY = 'sillytavern_auth_user';
const AUTH_PASS_KEY = 'sillytavern_auth_pass';
const BG_MODE_KEY = 'sillytavern_bg_mode';
const BUFFER_LIMIT_KEY = 'sillytavern_buffer_limit';

// Elements
const mainScreen = document.getElementById('main-screen');
const settingsScreen = document.getElementById('settings-screen');
const currentUrlDisplay = document.getElementById('current-url-display');
const urlInput = document.getElementById('url-input');
const authToggle = document.getElementById('auth-toggle');
const authFields = document.getElementById('auth-fields');
const authUser = document.getElementById('auth-user');
const authPass = document.getElementById('auth-pass');
const bgToggle = document.getElementById('bg-toggle');
const batteryOptBtn = document.getElementById('battery-opt-btn');
const bufferInput = document.getElementById('buffer-input');

// Buttons
const connectBtn = document.getElementById('connect-btn');
const settingsBtn = document.getElementById('settings-btn');
const saveBtn = document.getElementById('save-btn');
const cancelBtn = document.getElementById('cancel-btn');

function init() {
    const storedUrl = localStorage.getItem(STORAGE_KEY);

    // Sync settings to native side on startup
    syncAuthToNative();
    syncBackgroundToNative();
    syncBufferLimitToNative();

    if (!storedUrl) {
        const defaultUrl = 'http://127.0.0.1:8000';
        localStorage.setItem(STORAGE_KEY, defaultUrl);
        updateDisplay(defaultUrl);
        showSettings();
    } else {
        updateDisplay(storedUrl);
        connect();
    }
}

function updateDisplay(url) {
    if (url) {
        currentUrlDisplay.textContent = url;
        connectBtn.disabled = false;
        connectBtn.style.opacity = '1';
    } else {
        currentUrlDisplay.textContent = 'No URL Set';
        connectBtn.disabled = true;
        connectBtn.style.opacity = '0.5';
    }
}

function showSettings() {
    mainScreen.classList.add('hidden');
    settingsScreen.classList.remove('hidden');
    urlInput.value = localStorage.getItem(STORAGE_KEY) || '';

    const authEnabled = localStorage.getItem(AUTH_ENABLED_KEY) === 'true';
    authToggle.checked = authEnabled;
    authUser.value = localStorage.getItem(AUTH_USER_KEY) || '';
    authPass.value = localStorage.getItem(AUTH_PASS_KEY) || '';

    bgToggle.checked = localStorage.getItem(BG_MODE_KEY) === 'true';
    checkBatteryOptimization();

    var bufferLimit = localStorage.getItem(BUFFER_LIMIT_KEY);
    bufferInput.value = bufferLimit !== null ? parseInt(bufferLimit) : 100;

    toggleAuthFields();
}

function checkBatteryOptimization() {
    if (window.BackgroundBridge && window.BackgroundBridge.isIgnoringBatteryOptimizations) {
        if (window.BackgroundBridge.isIgnoringBatteryOptimizations()) {
            batteryOptBtn.classList.add('hidden');
        } else {
            batteryOptBtn.classList.remove('hidden');
        }
    }
}

function requestBatteryOptimization() {
    if (window.BackgroundBridge && window.BackgroundBridge.requestIgnoreBatteryOptimizations) {
        window.BackgroundBridge.requestIgnoreBatteryOptimizations();
        setTimeout(checkBatteryOptimization, 5000);
    }
}

function toggleAuthFields() {
    if (authToggle.checked) {
        authFields.classList.remove('hidden');
    } else {
        authFields.classList.add('hidden');
    }
}

function hideSettings() {
    settingsScreen.classList.add('hidden');
    mainScreen.classList.remove('hidden');
}

function saveSettings() {
    let url = urlInput.value.trim();
    if (!url) return;

    try {
        if (!/^https?:\/\//i.test(url)) {
            url = 'http://' + url;
        }
        const validUrl = new URL(url);
        url = validUrl.href;
        if (url.endsWith('/')) {
             url = url.slice(0, -1);
        }
    } catch (e) {
        alert('Invalid URL format. Please check the address.');
        return;
    }

    localStorage.setItem(STORAGE_KEY, url);

    localStorage.setItem(AUTH_ENABLED_KEY, authToggle.checked);
    if (authToggle.checked) {
        localStorage.setItem(AUTH_USER_KEY, authUser.value.trim());
        localStorage.setItem(AUTH_PASS_KEY, authPass.value);
    }

    localStorage.setItem(BG_MODE_KEY, bgToggle.checked);

    var bufferLimit = parseInt(bufferInput.value) || 100;
    if (bufferLimit < 0) bufferLimit = 0;
    if (bufferLimit > 10240) bufferLimit = 10240;
    localStorage.setItem(BUFFER_LIMIT_KEY, bufferLimit);

    syncAuthToNative();
    syncBackgroundToNative();
    syncBufferLimitToNative();
    updateDisplay(url);
    hideSettings();
}

function syncBackgroundToNative() {
    const enabled = localStorage.getItem(BG_MODE_KEY) === 'true';
    if (window.BackgroundBridge && window.BackgroundBridge.setBackgroundMode) {
        window.BackgroundBridge.setBackgroundMode(enabled);
    }
}

function syncBufferLimitToNative() {
    var limit = parseInt(localStorage.getItem(BUFFER_LIMIT_KEY)) || 100;
    if (limit < 0) limit = 0;
    if (limit > 10240) limit = 10240;
    if (window.BackgroundBridge && window.BackgroundBridge.setBufferLimit) {
        window.BackgroundBridge.setBufferLimit(limit);
    }
}

function syncAuthToNative() {
    const enabled = localStorage.getItem(AUTH_ENABLED_KEY) === 'true';
    const user = localStorage.getItem(AUTH_USER_KEY) || '';
    const pass = localStorage.getItem(AUTH_PASS_KEY) || '';

    if (window.AuthBridge) {
        if (enabled && user && pass) {
            window.AuthBridge.setCredentials(user, pass);
        } else {
            window.AuthBridge.clearCredentials();
        }
    }
}

function connect() {
    const url = localStorage.getItem(STORAGE_KEY);
    if (!url) return;

    const bgEnabled = localStorage.getItem(BG_MODE_KEY) === 'true';

    if (bgEnabled && window.BackgroundBridge && window.BackgroundBridge.startProxy) {
        // Route through local proxy for background streaming support
        connectViaProxy(url);
    } else {
        // Direct connection
        window.location.href = url;
    }
}

function connectViaProxy(realUrl) {
    // Fixed proxy port
    var proxyPort = 48765;
    var proxyUrl = 'http://127.0.0.1:' + proxyPort + '/';

    // Start the local proxy with the real server URL
    window.BackgroundBridge.startProxy(realUrl);

    // Give proxy a moment to start, then navigate
    localStorage.setItem(STORAGE_KEY, proxyUrl);
    updateDisplay(proxyUrl + ' (via proxy → ' + realUrl + ')');

    // Short delay to let proxy initialize, then navigate
    setTimeout(function() {
        window.location.href = proxyUrl;
    }, 300);
}

// Event Listeners
settingsBtn.addEventListener('click', showSettings);
cancelBtn.addEventListener('click', hideSettings);
saveBtn.addEventListener('click', saveSettings);
connectBtn.addEventListener('click', connect);
authToggle.addEventListener('change', toggleAuthFields);
batteryOptBtn.addEventListener('click', requestBatteryOptimization);

init();
