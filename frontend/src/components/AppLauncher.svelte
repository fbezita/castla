<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import type { StreamRuntime } from '../runtime/StreamRuntime';
  import { compositorStore } from '../stores/compositorStore';
  import type { PaneId } from '../protocol';

  export let runtime: StreamRuntime;

  interface AppInfo {
    packageName: string;
    label: string;
    componentName?: string;
    category?: string;
    isWeb?: boolean;
    left?: string;
    right?: string;
    isPair?: boolean;
  }

  interface AppPairRecord {
    left: string;
    right: string;
  }

  type DropZone = 'favorite' | 'autorun' | 'primary' | 'secondary' | 'remove' | '';
  const APP_CACHE_KEY = 'castla_cached_apps_v1';

  const groups = [
    ['PAIR', 'App Pairs', '#00e5ff'],
    ['FAVORITES', 'Favorites', '#ffd400'],
    ['NAVIGATION', 'Navigation', '#49d66d'],
    ['VIDEO', 'Video', '#ff6b43'],
    ['MUSIC', 'Music', '#b46cff'],
    ['OTHER', 'Apps', '#9ea3ad']
  ] as const;

  let apps: AppInfo[] = readCachedApps();
  let loading = apps.length === 0;
  let error = '';
  let drawerOpen = true;
  let drawerElement: HTMLElement;
  let drawerListElement: HTMLDivElement;
  let search = '';
  let favorites = readArray('castla_favorites');
  let appPairs = readPairs();
  let primaryAutorun = localStorage.getItem('castla_autorun_primary') ?? '';
  let secondaryAutorun = localStorage.getItem('castla_autorun_secondary') ?? '';
  let notice = '';
  let noticeTimer: number | undefined;
  let launchedOnce = false;

  let pressTimer = 0;
  let pressedApp: AppInfo | null = null;
  let draggingApp: AppInfo | null = null;
  let dragX = 0;
  let dragY = 0;
  let dropZone: DropZone = '';
  let drawerDimmed = false;
  let pressStartX = 0;
  let pressStartY = 0;
  let pressMoved = false;
  let pairTarget: AppInfo | null = null;
  let pairMenuOpen = '';
  let editingPair: AppInfo | null = null;

  onMount(() => {
    if (apps.length > 0) {
      runAutorunOnce();
    }
    loadApps();
  });
  onDestroy(() => {
    window.clearTimeout(pressTimer);
    window.clearTimeout(noticeTimer);
  });

  $: hasVisibleStream = Array.from($compositorStore.viewports.values()).some((viewport) => viewport.committed);
  $: pairApps = getPairApps();
  $: searchableApps = [...pairApps, ...apps];
  $: displayApps = searchableApps.filter((app) => app.label.toLowerCase().includes(search.trim().toLowerCase()));
  $: groupedApps = groups.map(([key, title, color]) => ({
    key,
    title,
    color,
    items: displayApps.filter((app) => belongsToGroup(app, key))
  })).filter((group) => group.items.length > 0);

  async function loadApps() {
    try {
      const response = await fetch('/api/apps');
      if (!response.ok) throw new Error(`apps ${response.status}`);
      const data = await response.json();
      apps = Array.isArray(data.apps) ? data.apps : [];
      localStorage.setItem(APP_CACHE_KEY, JSON.stringify(apps));
      runAutorunOnce();
      error = '';
    } catch (err) {
      if (apps.length === 0) {
        error = err instanceof Error ? err.message : String(err);
      }
    } finally {
      loading = false;
    }
  }

  function belongsToGroup(app: AppInfo, group: string) {
    if (group === 'PAIR') return app.isPair === true;
    if (group === 'FAVORITES') return favorites.includes(app.packageName);
    if (group === 'OTHER') return !['NAVIGATION', 'VIDEO', 'MUSIC'].includes(app.category ?? '');
    return app.category === group;
  }

  function launch(app: AppInfo, pane: PaneId = 'primary') {
    launchedOnce = true;
    if (pane === 'primary') setSingle('primary');
    else setSplit(true);
    syncLayout(pane === 'primary' ? 'single' : 'split');
    runtime.launchApp(app.packageName, pane, app.componentName, app.category === 'VIDEO' || app.isWeb === true);
    runtime.requestKeyframe(pane);
    drawerOpen = false;
    toast(`${app.label} launching`);
  }

  function launchPair(left: AppInfo, right: AppInfo) {
    launchedOnce = true;
    setSplit(true);
    syncLayout('split');
    runtime.launchApp(left.packageName, 'primary', left.componentName, left.category === 'VIDEO' || left.isWeb === true);
    runtime.requestKeyframe('primary');
    setTimeout(() => {
      runtime.launchApp(right.packageName, 'secondary', right.componentName, right.category === 'VIDEO' || right.isWeb === true);
      runtime.requestKeyframe('secondary');
    }, 260);
    drawerOpen = false;
    toast(`${left.label} + ${right.label}`);
  }

  function activateApp(app: AppInfo) {
    if (app.isPair) {
      const left = app.left ? apps.find((candidate) => candidate.packageName === app.left) : undefined;
      const right = app.right ? apps.find((candidate) => candidate.packageName === app.right) : undefined;
      if (left && right) {
        launchPair(left, right);
        return;
      }
    }
    launch(app, 'primary');
  }

  function setSingle(pane: PaneId) {
    compositorStore.update((state) => {
      const viewports = new Map(state.viewports);
      const primary = viewports.get('primary') ?? { pane: 'primary' as PaneId, width: 1280, height: 720, committed: false, generation: 0, visible: true };
      const secondary = viewports.get('secondary') ?? { pane: 'secondary' as PaneId, width: 1280, height: 720, committed: false, generation: 0, visible: false };
      viewports.set('primary', { ...primary, visible: pane === 'primary' });
      viewports.set('secondary', { ...secondary, visible: pane === 'secondary' });
      return { ...state, viewports, layoutMode: 'single' };
    });
  }

  function setSplit(active: boolean) {
    compositorStore.update((state) => {
      const viewports = new Map(state.viewports);
      const primary = viewports.get('primary') ?? { pane: 'primary' as PaneId, width: 1280, height: 720, committed: false, generation: 0, visible: true };
      const secondary = viewports.get('secondary') ?? { pane: 'secondary' as PaneId, width: 1280, height: 720, committed: false, generation: 0, visible: false };
      viewports.set('primary', { ...primary, visible: true });
      viewports.set('secondary', { ...secondary, visible: active });
      return { ...state, viewports, layoutMode: active ? 'split' : 'single' };
    });
  }

  function toggleFavorite(packageName: string) {
    favorites = favorites.includes(packageName) ? favorites.filter((pkg) => pkg !== packageName) : [...favorites, packageName];
    localStorage.setItem('castla_favorites', JSON.stringify(favorites));
  }

  function getPairApps(): AppInfo[] {
    const result: AppInfo[] = [];
    for (const pair of appPairs) {
      const leftApp = apps.find((app) => app.packageName === pair.left);
      const rightApp = apps.find((app) => app.packageName === pair.right);
      if (!leftApp || !rightApp) continue;
      result.push({
        packageName: `pair:${pair.left}:${pair.right}`,
        label: `${leftApp.label} + ${rightApp.label}`,
        category: 'PAIR',
        isPair: true,
        left: pair.left,
        right: pair.right
      });
    }
    return result;
  }

  function createPair(source: AppInfo, target: AppInfo) {
    if (source.isPair || target.isPair) return;
    if (source.packageName === target.packageName) {
      toast('Choose a different app');
      return;
    }
    const exists = appPairs.some((pair) =>
      (pair.left === source.packageName && pair.right === target.packageName) ||
      (pair.left === target.packageName && pair.right === source.packageName)
    );
    if (exists) {
      toast('This App Pair already exists');
      return;
    }
    appPairs = [...appPairs, { left: source.packageName, right: target.packageName }];
    localStorage.setItem('castla_app_pairs', JSON.stringify(appPairs));
    openPairEdit({
      packageName: `pair:${source.packageName}:${target.packageName}`,
      label: `${source.label} + ${target.label}`,
      category: 'PAIR',
      isPair: true,
      left: source.packageName,
      right: target.packageName
    });
    toast(`${source.label} + ${target.label}`);
  }

  function openPairEdit(app: AppInfo) {
    if (!app.left || !app.right) return;
    editingPair = { ...app };
    pairMenuOpen = '';
  }

  function swapEditingPair() {
    if (!editingPair?.left || !editingPair?.right) return;
    editingPair = {
      ...editingPair,
      left: editingPair.right,
      right: editingPair.left
    };
  }

  function persistPair(app: AppInfo, previousLeft?: string, previousRight?: string) {
    if (!app.left || !app.right) return;
    const oldLeft = previousLeft ?? app.left;
    const oldRight = previousRight ?? app.right;
    const nextPair = { left: app.left, right: app.right };
    const index = appPairs.findIndex((pair) =>
      (pair.left === oldLeft && pair.right === oldRight) ||
      (pair.left === oldRight && pair.right === oldLeft)
    );
    if (index >= 0) {
      appPairs = appPairs.map((pair, pairIndex) => (pairIndex === index ? nextPair : pair));
    } else {
      appPairs = [...appPairs, nextPair];
    }
    localStorage.setItem('castla_app_pairs', JSON.stringify(appPairs));
  }

  function saveEditingPair() {
    if (!editingPair?.left || !editingPair?.right) return;
    const nextPair = editingPair;
    const original = getPairApps().find((app) => app.packageName === nextPair.packageName);
    persistPair(nextPair, original?.left, original?.right);
    editingPair = null;
    toast('App Pair updated');
  }

  function removePair(app: AppInfo) {
    if (!app.left || !app.right) return;
    appPairs = appPairs.filter((pair) =>
      !((pair.left === app.left && pair.right === app.right) || (pair.left === app.right && pair.right === app.left))
    );
    localStorage.setItem('castla_app_pairs', JSON.stringify(appPairs));
    if (editingPair?.packageName === app.packageName) editingPair = null;
    pairMenuOpen = '';
    toast('App Pair dissolved');
  }

  function cancelEditingPair() {
    editingPair = null;
  }

  function togglePairMenu(app: AppInfo) {
    pairMenuOpen = pairMenuOpen === app.packageName ? '' : app.packageName;
  }

  function toggleAutorun(packageName: string) {
    if (primaryAutorun === packageName || secondaryAutorun === packageName) {
      if (primaryAutorun === packageName) primaryAutorun = '';
      if (secondaryAutorun === packageName) secondaryAutorun = '';
    } else if (!primaryAutorun) {
      primaryAutorun = packageName;
    } else {
      secondaryAutorun = packageName;
    }
    updateStorage('castla_autorun_primary', primaryAutorun);
    updateStorage('castla_autorun_secondary', secondaryAutorun);
  }

  function runAutorunOnce() {
    if (sessionStorage.getItem('castla_autorun_done') === '1') return;
    sessionStorage.setItem('castla_autorun_done', '1');
    const primary = apps.find((app) => app.packageName === primaryAutorun);
    const secondary = apps.find((app) => app.packageName === secondaryAutorun);
    if (primary && secondary) launchPair(primary, secondary);
    else if (primary) launch(primary, 'primary');
  }

  function startPress(event: PointerEvent, app: AppInfo) {
    const target = event.target as HTMLElement;
    if (target.closest('button')) return;
    pairMenuOpen = '';
    event.preventDefault();
    (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
    pressedApp = app;
    pressStartX = event.clientX;
    pressStartY = event.clientY;
    pressMoved = false;
    dragX = event.clientX;
    dragY = event.clientY;
    window.clearTimeout(pressTimer);
    pressTimer = window.setTimeout(() => {
      draggingApp = pressedApp;
      navigator.vibrate?.(50);
      drawerOpen = true;
      updateDropZone(dragX, dragY);
    }, 1000);
  }

  function movePress(event: PointerEvent) {
    if (!pressedApp && !draggingApp) return;
    dragX = event.clientX;
    dragY = event.clientY;
    if (Math.hypot(dragX - pressStartX, dragY - pressStartY) > 10) pressMoved = true;
    if (draggingApp) {
      autoScrollDrawer(dragY);
      updateDropZone(dragX, dragY);
    }
  }

  function endPress(event?: PointerEvent) {
    window.clearTimeout(pressTimer);
    if (draggingApp) {
      if (pairTarget) createPair(draggingApp, pairTarget);
      else if (dropZone) applyDrop(draggingApp, dropZone);
    } else if (pressedApp && !pressMoved) {
      activateApp(pressedApp);
    }
    if (event?.currentTarget instanceof HTMLElement) {
      try { event.currentTarget.releasePointerCapture(event.pointerId); } catch {}
    }
    pressedApp = null;
    draggingApp = null;
    dropZone = '';
    drawerDimmed = false;
    pressMoved = false;
    pairTarget = null;
  }

  function updateDropZone(x: number, y: number) {
    if (isPointInsideDrawer(x, y)) {
      pairTarget = findPairTarget(x, y);
      dropZone = '';
      drawerDimmed = false;
      return;
    }
    pairTarget = null;
    drawerDimmed = true;
    const w = window.innerWidth;
    const h = window.innerHeight;
    if (y < h * 0.16 && x < w * 0.5) dropZone = 'favorite';
    else if (y < h * 0.16) dropZone = 'autorun';
    else if (y > h * 0.86) dropZone = 'remove';
    else if (x < w * 0.5) dropZone = 'primary';
    else dropZone = 'secondary';
  }

  function autoScrollDrawer(y: number) {
    if (!drawerListElement || !drawerOpen) return;
    const rect = drawerListElement.getBoundingClientRect();
    const edgeSize = 88;
    const maxStep = 16;
    if (y > rect.bottom - edgeSize && y < rect.bottom + 24) {
      const intensity = Math.min(1, (y - (rect.bottom - edgeSize)) / edgeSize);
      drawerListElement.scrollTop += Math.ceil(maxStep * intensity);
    } else if (y < rect.top + edgeSize && y > rect.top - 24) {
      const intensity = Math.min(1, ((rect.top + edgeSize) - y) / edgeSize);
      drawerListElement.scrollTop -= Math.ceil(maxStep * intensity);
    }
  }

  function applyDrop(app: AppInfo, zone: DropZone) {
    if (app.isPair && app.left && app.right) {
      if (zone === 'autorun') {
        primaryAutorun = app.left;
        secondaryAutorun = app.right;
        updateStorage('castla_autorun_primary', primaryAutorun);
        updateStorage('castla_autorun_secondary', secondaryAutorun);
        toast(`${app.label} set to Auto-run`);
        return;
      }
      if (zone === 'remove') {
        removePair(app);
        return;
      }
    }
    if (zone === 'favorite') {
      toggleFavorite(app.packageName);
      toast(favorites.includes(app.packageName) ? 'Favorite updated' : 'Favorite removed');
    } else if (zone === 'autorun') {
      toggleAutorun(app.packageName);
      toast('Auto-run updated');
    } else if (zone === 'primary') {
      launch(app, 'primary');
    } else if (zone === 'secondary') {
      launch(app, 'secondary');
    } else if (zone === 'remove') {
      favorites = favorites.filter((pkg) => pkg !== app.packageName);
      if (primaryAutorun === app.packageName) primaryAutorun = '';
      if (secondaryAutorun === app.packageName) secondaryAutorun = '';
      localStorage.setItem('castla_favorites', JSON.stringify(favorites));
      updateStorage('castla_autorun_primary', primaryAutorun);
      updateStorage('castla_autorun_secondary', secondaryAutorun);
      toast('Removed from shortcuts');
    }
  }

  function isPointInsideDrawer(x: number, y: number): boolean {
    if (!drawerElement || !drawerOpen) return false;
    const rect = drawerElement.getBoundingClientRect();
    return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
  }

  function syncLayout(mode: 'single' | 'split') {
    const host = document.querySelector('.viewport-host');
    if (!(host instanceof HTMLElement)) return;
    const rect = host.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;

    if (mode === 'single') {
      runtime.sendLayout([
        {
          id: 'primary',
          width: rect.width,
          height: rect.height,
          visible: true
        },
        {
          id: 'secondary',
          width: rect.width,
          height: rect.height,
          visible: false
        }
      ]);
      return;
    }

    const ratio = $compositorStore.splitRatio;
    const reversed = $compositorStore.splitReversed;
    const primaryWidth = Math.max(320, Math.round(rect.width * ratio));
    const secondaryWidth = Math.max(320, Math.round(rect.width - primaryWidth));
    const leftPane: PaneId = reversed ? 'secondary' : 'primary';
    const rightPane: PaneId = reversed ? 'primary' : 'secondary';

    runtime.sendLayout([
      {
        id: leftPane,
        width: leftPane === 'primary' ? primaryWidth : secondaryWidth,
        height: rect.height,
        visible: true
      },
      {
        id: rightPane,
        width: rightPane === 'primary' ? primaryWidth : secondaryWidth,
        height: rect.height,
        visible: true
      }
    ]);
  }

  function findPairTarget(x: number, y: number): AppInfo | null {
    const hovered = document.elementFromPoint(x, y)?.closest('.split-app-item') as HTMLElement | null;
    const packageName = hovered?.dataset.packageName;
    if (!packageName || !draggingApp || packageName === draggingApp.packageName) return null;
    const target = apps.find((app) => app.packageName === packageName);
    if (!target || target.isPair) return null;
    return target;
  }

  function toast(message: string) {
    notice = message;
    clearTimeout(noticeTimer);
    noticeTimer = window.setTimeout(() => (notice = ''), 2600);
  }

  function readArray(key: string): string[] {
    try {
      const value = JSON.parse(localStorage.getItem(key) ?? '[]');
      return Array.isArray(value) ? value : [];
    } catch {
      return [];
    }
  }

  function readPairs(): AppPairRecord[] {
    try {
      const value = JSON.parse(localStorage.getItem('castla_app_pairs') ?? '[]');
      if (!Array.isArray(value)) return [];
      return value.filter((pair): pair is AppPairRecord =>
        pair && typeof pair.left === 'string' && typeof pair.right === 'string' && pair.left !== pair.right
      );
    } catch {
      return [];
    }
  }

  function readCachedApps(): AppInfo[] {
    try {
      const value = JSON.parse(localStorage.getItem(APP_CACHE_KEY) ?? '[]');
      return Array.isArray(value) ? value : [];
    } catch {
      return [];
    }
  }

  function updateStorage(key: string, value: string) {
    if (value) localStorage.setItem(key, value);
    else localStorage.removeItem(key);
  }
</script>

<div class:hidden={launchedOnce || hasVisibleStream} class="standby">
  <div class="status-mark">✓</div>
  <div class="standby-logo">CASTLA</div>
  <p>Ready to Stream. Open the sidebar drawer to launch an app.</p>
  <div class="server-pill"><span></span>SERVER ACTIVE</div>
</div>

<aside bind:this={drawerElement} class:open={drawerOpen} class:dimmed={drawerDimmed} class:dragging={Boolean(draggingApp)} class="split-drawer" on:contextmenu|preventDefault>
  <button class="split-handle" on:click={() => (drawerOpen = !drawerOpen)} aria-label="Launcher">
    <span></span><span></span><span></span>
  </button>
  <header>
    <strong>Launcher</strong>
    <span>{loading ? 'Loading' : `${apps.length} apps`}</span>
  </header>
  <div class="search-row">
    <input bind:value={search} placeholder="Search apps" autocomplete="off" />
  </div>
  {#if error}<div class="notice error">{error}</div>{/if}
  {#if notice}<div class="notice">{notice}</div>{/if}
  <div bind:this={drawerListElement} class="split-app-list">
    {#each groupedApps as group (group.key)}
      <section class="split-category-section">
        <div class="split-category-header">
          <span class="split-category-bar" style={`background:${group.color}`}></span>
          <span class="split-category-title">{group.title}</span>
        </div>
        <div class="split-category-items">
          {#each group.items as app (app.packageName)}
            <div
              data-package-name={app.isPair ? undefined : app.packageName}
              class:pair-target={pairTarget?.packageName === app.packageName}
              class:merge-target={pairTarget?.packageName === app.packageName && draggingApp !== null}
              class:drag-source={draggingApp?.packageName === app.packageName}
              class="split-app-item"
              title={app.label}
              on:pointerdown={(event) => startPress(event, app)}
              on:pointermove={movePress}
              on:pointerup={endPress}
              on:pointercancel={endPress}
              on:keydown={(event) => { if (event.key === 'Enter' || event.key === ' ') activateApp(app); }}
              on:contextmenu|preventDefault
              role="button"
              tabindex="0"
            >
              {#if app.isPair && app.left && app.right}
                <div class="pair-icons split-pair-icon">
                  <img class="split-app-icon pair-half pair-left-half" src={`/api/icon?pkg=${encodeURIComponent(app.left)}`} alt="" loading="lazy" draggable="false" />
                  <img class="split-app-icon pair-half pair-right-half" src={`/api/icon?pkg=${encodeURIComponent(app.right)}`} alt="" loading="lazy" draggable="false" />
                  <div class="pair-seam"></div>
                </div>
              {:else}
                <img class="split-app-icon" src={`/api/icon?pkg=${encodeURIComponent(app.packageName)}`} alt="" loading="lazy" draggable="false" />
              {/if}
              <button class="launch-main" on:click|stopPropagation={() => activateApp(app)}><span>{app.label}</span></button>
              {#if app.isPair}
                <button class:active={pairMenuOpen === app.packageName} class="pair-settings" title="Pair settings" on:click|stopPropagation={() => openPairEdit(app)}>⚙️</button>
              {:else}
                <button class:primary={primaryAutorun === app.packageName} class:secondary={secondaryAutorun === app.packageName} class="bolt" title="Auto-run" on:click|stopPropagation={() => toggleAutorun(app.packageName)}>↯</button>
                <button class:active={favorites.includes(app.packageName)} class="star" title="Favorite" on:click|stopPropagation={() => toggleFavorite(app.packageName)}>★</button>
              {/if}
              {#if pairTarget?.packageName === app.packageName && draggingApp}
                <div class="merge-preview" aria-hidden="true">
                  <div class="merge-icon incoming">
                    <img src={`/api/icon?pkg=${encodeURIComponent(draggingApp.packageName)}`} alt="" draggable="false" />
                  </div>
                  <div class="merge-plus">+</div>
                  <div class="merge-icon target">
                    <img src={`/api/icon?pkg=${encodeURIComponent(app.packageName)}`} alt="" draggable="false" />
                  </div>
                  <div class="merge-result">
                    <img class="merge-half left" src={`/api/icon?pkg=${encodeURIComponent(draggingApp.packageName)}`} alt="" draggable="false" />
                    <img class="merge-half right" src={`/api/icon?pkg=${encodeURIComponent(app.packageName)}`} alt="" draggable="false" />
                  </div>
                </div>
              {/if}
            </div>
          {/each}
        </div>
      </section>
    {/each}
  </div>
</aside>

{#if draggingApp && !pairTarget}
  <div class="drop-overlay">
    <div class:active={dropZone === 'favorite'} class:hidden={pairTarget !== null} class="drop-zone shortcut favorite-zone"><strong>★</strong><span>즐겨찾기 추가</span></div>
    <div class:active={dropZone === 'autorun'} class:hidden={pairTarget !== null} class="drop-zone shortcut autorun-zone"><strong>↯</strong><span>자동실행 등록</span></div>
    <div class:active={dropZone === 'primary'} class:hidden={pairTarget !== null} class="drop-zone primary-zone"><strong>▰</strong><span>Primary(왼쪽)에 실행</span><small>빈 화면 (VD_1)</small></div>
    <div class:active={dropZone === 'secondary'} class:hidden={pairTarget !== null} class="drop-zone secondary-zone"><strong>▰</strong><span>Secondary(오른쪽)에 실행</span><small>빈 화면 (VD_2)</small></div>
    <div class:active={dropZone === 'remove'} class:hidden={pairTarget !== null} class="drop-zone remove-zone"><strong>⌫</strong><span>제거 / 휴지통</span></div>
    <div class="drag-ghost" style={`left:${dragX}px;top:${dragY}px`}>
      <img src={`/api/icon?pkg=${encodeURIComponent(draggingApp.packageName)}`} alt="" />
    </div>
  </div>
{/if}

{#if editingPair}
  {@const pair = editingPair}
  <div
    class="pair-dialog-overlay"
    role="button"
    tabindex="0"
    aria-label="Close App Pair editor"
    on:click|self={cancelEditingPair}
    on:keydown={(event) => {
      if (event.key === 'Escape' || event.key === 'Enter' || event.key === ' ') cancelEditingPair();
    }}
  >
    <div
      class="pair-dialog"
      role="dialog"
      aria-modal="true"
      aria-label="App Pair editor"
      tabindex="-1"
    >
      <header class="pair-dialog-header">
        <strong>App Pair</strong>
      </header>
      <div class="pair-dialog-body">
        <div class="pair-dialog-app">
          <img src={`/api/icon?pkg=${encodeURIComponent(pair.left ?? '')}`} alt="" draggable="false" />
          <span>{apps.find((app) => app.packageName === pair.left)?.label ?? 'Unknown'}</span>
        </div>
        <button class="pair-dialog-swap" on:click={swapEditingPair}>⇄</button>
        <div class="pair-dialog-app">
          <img src={`/api/icon?pkg=${encodeURIComponent(pair.right ?? '')}`} alt="" draggable="false" />
          <span>{apps.find((app) => app.packageName === pair.right)?.label ?? 'Unknown'}</span>
        </div>
      </div>
      <div class="pair-dialog-actions">
        <button on:click={cancelEditingPair}>취소</button>
        <button class="danger" on:click={() => removePair(pair)}>분리하기</button>
        <button class="primary" on:click={saveEditingPair}>저장</button>
      </div>
    </div>
  </div>
{/if}

<style>
  .standby {
    position: absolute;
    inset: 0;
    z-index: 8;
    display: grid;
    place-content: center;
    justify-items: center;
    text-align: center;
    color: #eaf7ff;
    background: radial-gradient(circle at center, #171724 0%, #090a12 68%, #06070d 100%);
    pointer-events: none;
  }

  .standby.hidden {
    opacity: 0;
  }

  .status-mark {
    width: 96px;
    height: 96px;
    display: grid;
    place-items: center;
    border: 3px solid #28c9ff;
    border-radius: 50%;
    box-shadow: 0 0 40px rgb(40 201 255 / 0.45), inset 0 0 25px rgb(158 75 255 / 0.3);
    color: #8c74ff;
    font-size: 52px;
    margin-bottom: 34px;
  }

  .standby-logo {
    font-size: 36px;
    font-weight: 800;
    letter-spacing: 8px;
    background: linear-gradient(90deg, #22d6ff, #bd5cff);
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
  }

  .standby p {
    width: min(340px, 70vw);
    color: #898a99;
    line-height: 1.45;
    margin: 16px 0 30px;
  }

  .server-pill {
    display: inline-flex;
    align-items: center;
    gap: 9px;
    min-height: 36px;
    padding: 0 18px;
    border: 1px solid rgb(255 255 255 / 0.12);
    border-radius: 18px;
    background: rgb(255 255 255 / 0.06);
    color: #e1e4ee;
    font-size: 12px;
    font-weight: 800;
    letter-spacing: 0.8px;
  }

  .server-pill span {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: #12d8ff;
    box-shadow: 0 0 10px #12d8ff;
  }

  .split-drawer {
    position: absolute;
    top: 0;
    right: -300px;
    bottom: 0;
    width: 300px;
    z-index: 40;
    display: flex;
    flex-direction: column;
    color: white;
    background: rgb(18 18 28 / 0.97);
    border-left: 1px solid rgb(255 255 255 / 0.1);
    box-shadow: -8px 0 24px rgb(0 0 0 / 0.42);
    transition: right 0.24s ease, opacity 0.18s ease, filter 0.18s ease;
  }

  .split-drawer.open {
    right: 0;
  }

  .split-drawer.dragging {
    z-index: 82;
  }

  .split-drawer.dimmed {
    opacity: 0.28;
    filter: saturate(0.45);
  }

  .split-handle {
    position: absolute;
    left: -28px;
    top: 50%;
    width: 28px;
    height: 80px;
    transform: translateY(-50%);
    border: 1px solid rgb(255 255 255 / 0.1);
    border-right: 0;
    border-radius: 14px 0 0 14px;
    background: rgb(20 20 30 / 0.88);
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 5px;
  }

  .split-handle span {
    width: 4px;
    height: 4px;
    border-radius: 50%;
    background: rgb(255 255 255 / 0.65);
  }

  header,
  .search-row {
    padding: 14px 18px;
    border-bottom: 1px solid rgb(255 255 255 / 0.1);
  }

  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    min-height: 66px;
  }

  header strong {
    font-size: 20px;
  }

  header span {
    color: #a9adba;
    font-size: 12px;
  }

  input,
  button {
    font: inherit;
  }

  button {
    cursor: pointer;
  }

  .search-row input {
    width: 100%;
    height: 36px;
    border: 1px solid rgb(255 255 255 / 0.12);
    border-radius: 8px;
    background: rgb(255 255 255 / 0.08);
    color: white;
    padding: 0 10px;
  }

  .notice {
    margin: 10px;
    padding: 8px 10px;
    border-radius: 8px;
    background: rgb(0 229 255 / 0.16);
    font-size: 12px;
  }

  .notice.error {
    background: rgb(255 70 70 / 0.2);
  }

  .split-app-list {
    flex: 1;
    overflow: auto;
    padding: 10px 6px 14px 10px;
  }

  .split-category-section {
    margin-bottom: 22px;
  }

  .split-category-header {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 4px 8px 8px;
  }

  .split-category-bar {
    width: 4px;
    height: 18px;
    border-radius: 2px;
  }

  .split-category-title {
    color: #d8d9df;
    font-size: 16px;
    font-weight: 800;
  }

  .split-category-items {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .split-app-item {
    position: relative;
    display: grid;
    grid-template-columns: 48px 1fr 28px 28px;
    align-items: center;
    gap: 8px;
    min-height: 60px;
    padding: 8px 10px;
    border-radius: 8px;
    background: rgb(255 255 255 / 0.075);
    user-select: none;
    touch-action: none;
    -webkit-user-drag: none;
    transition: transform 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;
  }

  .split-app-item.drag-source {
    opacity: 0.42;
  }

  .split-app-item.pair-target {
    outline: 2px solid #00e5ff;
    box-shadow: 0 0 0 1px rgb(0 229 255 / 0.25), inset 0 0 24px rgb(0 229 255 / 0.18);
    background: rgb(0 229 255 / 0.12);
  }

  .split-app-item.merge-target {
    transform: scale(1.02);
  }

  .pair-icons {
    position: relative;
    width: 48px;
    height: 42px;
  }

  .split-pair-icon {
    width: 42px;
    height: 42px;
    border-radius: 12px;
    overflow: hidden;
    box-shadow: 0 4px 12px rgb(0 0 0 / 0.25);
    background: rgb(255 255 255 / 0.08);
  }

  .split-app-icon {
    width: 42px;
    height: 42px;
    object-fit: contain;
    -webkit-user-drag: none;
    user-select: none;
  }

  .pair-half {
    position: absolute;
    inset: 0;
    width: 42px;
    height: 42px;
    object-fit: cover;
  }

  .pair-left-half {
    clip-path: inset(0 50% 0 0);
  }

  .pair-right-half {
    clip-path: inset(0 0 0 50%);
  }

  .pair-seam {
    position: absolute;
    top: 6px;
    bottom: 6px;
    left: 50%;
    width: 1px;
    transform: translateX(-50%);
    background: rgb(255 255 255 / 0.65);
    box-shadow: 0 0 6px rgb(255 255 255 / 0.3);
  }

  .launch-main,
  .star,
  .bolt {
    border: 0;
    color: white;
    background: transparent;
  }

  .launch-main {
    min-width: 0;
    text-align: left;
    font-size: 16px;
    font-weight: 700;
  }

  .launch-main span {
    display: -webkit-box;
    overflow: hidden;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
  }

  .star,
  .bolt {
    width: 28px;
    height: 28px;
    border-radius: 50%;
    color: rgb(255 255 255 / 0.88);
    font-size: 22px;
  }

  .star.active {
    color: #ffd700;
    text-shadow: 0 0 14px #ffd700;
  }

  .bolt {
    color: rgb(255 110 78 / 0.42);
  }

  .bolt.primary,
  .bolt.secondary {
    color: #ff7043;
  }

  .pair-settings {
    width: 32px;
    height: 28px;
    border: 0;
    border-radius: 14px;
    background: rgb(0 229 255 / 0.14);
    color: #00e5ff;
    font-size: 16px;
    line-height: 1;
  }

  .pair-settings.active {
    background: rgb(0 229 255 / 0.24);
    box-shadow: 0 0 0 1px rgb(0 229 255 / 0.25);
  }

  .merge-preview {
    position: absolute;
    inset: 0;
    z-index: 2;
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 10px;
    border-radius: 8px;
    background: linear-gradient(90deg, rgb(0 229 255 / 0.12), rgb(0 229 255 / 0.04));
    overflow: hidden;
  }

  .merge-icon,
  .merge-result {
    position: relative;
    width: 34px;
    height: 34px;
    border-radius: 10px;
    overflow: hidden;
    background: rgb(255 255 255 / 0.14);
    box-shadow: 0 6px 14px rgb(0 0 0 / 0.2);
    flex: 0 0 auto;
  }

  .merge-icon img,
  .merge-result img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .merge-icon.incoming {
    animation: merge-slide-in 0.36s ease both;
  }

  .merge-icon.target {
    animation: merge-pulse 0.36s ease both;
  }

  .merge-plus {
    color: #8ff3ff;
    font-weight: 800;
    font-size: 18px;
  }

  .merge-result {
    margin-left: auto;
  }

  .merge-half {
    position: absolute;
    inset: 0;
  }

  .merge-half.left {
    clip-path: inset(0 50% 0 0);
  }

  .merge-half.right {
    clip-path: inset(0 0 0 50%);
  }

  @keyframes merge-slide-in {
    from {
      transform: translateX(-22px) scale(0.88);
      opacity: 0.2;
    }
    to {
      transform: translateX(0) scale(1);
      opacity: 1;
    }
  }

  @keyframes merge-pulse {
    0% {
      transform: scale(1);
    }
    50% {
      transform: scale(1.08);
    }
    100% {
      transform: scale(1);
    }
  }

  .drop-overlay {
    position: absolute;
    inset: 0;
    z-index: 80;
    background: rgb(4 5 12 / 0.76);
    backdrop-filter: blur(2px);
    pointer-events: none;
  }

  .drop-zone {
    position: absolute;
    display: grid;
    place-content: center;
    justify-items: center;
    border: 2px dashed rgb(255 255 255 / 0.18);
    color: rgb(255 255 255 / 0.78);
    background: rgb(10 12 20 / 0.32);
  }

  .drop-zone strong {
    font-size: 34px;
    margin-bottom: 6px;
  }

  .drop-zone span {
    font-weight: 800;
  }

  .drop-zone small {
    margin-top: 8px;
    color: rgb(255 255 255 / 0.45);
    font-size: 12px;
  }

  .shortcut {
    top: 0;
    width: 49%;
    height: 13%;
    border-radius: 0 0 18px 18px;
  }

  .favorite-zone {
    left: 0;
  }

  .autorun-zone {
    right: 0;
  }

  .primary-zone,
  .secondary-zone {
    top: 15%;
    bottom: 15%;
    width: 49%;
    border-radius: 22px;
  }

  .primary-zone {
    left: 0;
    border-color: rgb(0 229 255 / 0.48);
    color: #00e5ff;
  }

  .secondary-zone {
    right: 0;
    border-color: rgb(224 64 251 / 0.48);
    color: #e040fb;
  }

  .remove-zone {
    left: 0;
    right: 0;
    bottom: 0;
    height: 13%;
    border-radius: 18px 18px 0 0;
    border-color: rgb(255 61 61 / 0.36);
    color: #ff4343;
  }

  .drop-zone.active {
    background: rgb(255 255 255 / 0.08);
    box-shadow: inset 0 0 28px currentColor;
  }

  .drop-zone.hidden {
    opacity: 0;
  }

  .drag-ghost {
    position: absolute;
    width: 78px;
    height: 78px;
    transform: translate(-50%, -50%);
    display: grid;
    place-items: center;
    border-radius: 24px;
    background: rgb(255 255 255 / 0.82);
    box-shadow: 0 12px 34px rgb(0 0 0 / 0.45);
    pointer-events: none;
    z-index: 2;
  }

  .drag-ghost img {
    width: 58px;
    height: 58px;
    object-fit: contain;
  }

  .pair-dialog-overlay {
    position: absolute;
    inset: 0;
    z-index: 90;
    display: grid;
    place-items: center;
    background: rgb(4 5 12 / 0.72);
    backdrop-filter: blur(3px);
  }

  .pair-dialog {
    width: min(320px, calc(100vw - 32px));
    padding: 18px;
    border: 1px solid rgb(255 255 255 / 0.12);
    border-radius: 18px;
    background: rgb(13 18 28 / 0.98);
    color: white;
    box-shadow: 0 18px 42px rgb(0 0 0 / 0.42);
  }

  .pair-dialog-header {
    margin-bottom: 14px;
  }

  .pair-dialog-header strong {
    font-size: 18px;
  }

  .pair-dialog-body {
    display: grid;
    grid-template-columns: 1fr 40px 1fr;
    align-items: center;
    gap: 10px;
    margin-bottom: 16px;
  }

  .pair-dialog-app {
    display: grid;
    justify-items: center;
    gap: 8px;
    min-width: 0;
  }

  .pair-dialog-app img {
    width: 54px;
    height: 54px;
    object-fit: contain;
  }

  .pair-dialog-app span {
    max-width: 100%;
    text-align: center;
    font-size: 13px;
    line-height: 1.3;
    word-break: break-word;
  }

  .pair-dialog-swap {
    width: 40px;
    height: 40px;
    border: 0;
    border-radius: 999px;
    background: rgb(255 255 255 / 0.12);
    color: #00e5ff;
    font-size: 20px;
  }

  .pair-dialog-actions {
    display: flex;
    gap: 8px;
    justify-content: flex-end;
  }

  .pair-dialog-actions button {
    height: 36px;
    padding: 0 12px;
    border: 0;
    border-radius: 10px;
    background: rgb(255 255 255 / 0.08);
    color: white;
  }

  .pair-dialog-actions .danger {
    color: #ff7d7d;
    background: rgb(255 72 72 / 0.14);
  }

  .pair-dialog-actions .primary {
    color: #031217;
    background: #00e5ff;
    font-weight: 700;
  }
</style>
