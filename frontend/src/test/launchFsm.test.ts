import { describe, it, expect, vi, beforeEach } from 'vitest';
import { writable, get } from 'svelte/store';
import { compositorStore, type LaunchState, type LaunchSequence } from '../stores/compositorStore';

// Custom error classes exactly mirroring AppLauncher.svelte
class StaleLaunchSequenceError extends Error {
  constructor() {
    super('stale_launch_sequence');
    this.name = 'StaleLaunchSequenceError';
  }
}

describe('Castla E2E ACK Driven Launch State Machine Unit Tests', () => {
  let currentSeqId = 0;
  
  // Simulated message subscription listeners
  let ackListeners: Array<(msg: any) => void> = [];

  function nextLaunchSeqId(): number {
    return ++currentSeqId;
  }

  function registerAckListener(cb: (msg: any) => void) {
    ackListeners.push(cb);
    return () => {
      ackListeners = ackListeners.filter(l => l !== cb);
    };
  }

  function emitAckMessage(msg: any) {
    ackListeners.forEach(l => l(msg));
  }

  // Simulated isStale condition check
  function isStale(seqId: number): boolean {
    const active = get(compositorStore).launchSequence;
    return active.id !== seqId;
  }

  // Helper function: wait for layout ACK
  function waitForLayoutAck(seqId: number, expectedMode: string, timeoutMs = 100): Promise<void> {
    return new Promise((resolve, reject) => {
      const unsub = registerAckListener((msg) => {
        if (isStale(seqId)) { unsub(); reject(new StaleLaunchSequenceError()); return; }
        if (msg.type === 'layout_ack' && msg.seqId === seqId) {
          unsub();
          if (msg.success) resolve();
          else reject(new Error('layout_ack_failed'));
        }
      });
      setTimeout(() => { unsub(); reject(new Error('layout_ack_timeout')); }, timeoutMs);
    });
  }

  // Helper function: wait for launch ACK
  function waitForLaunchAck(seqId: number, pane: 'primary' | 'secondary', timeoutMs = 100): Promise<void> {
    return new Promise((resolve, reject) => {
      const unsub = registerAckListener((msg) => {
        if (isStale(seqId)) { unsub(); reject(new StaleLaunchSequenceError()); return; }
        if (msg.type === 'launch_ack' && msg.seqId === seqId && msg.pane === pane) {
          unsub();
          if (msg.success) resolve();
          else reject(new Error('launch_failure'));
        } else if (msg.type === 'launch_failed' && msg.seqId === seqId && msg.pane === pane) {
          unsub();
          reject(new Error('launch_failure'));
        }
      });
      setTimeout(() => { unsub(); reject(new Error('launch_failure')); }, timeoutMs);
    });
  }

  // Helper function: wait for session ready
  function waitForSessionReady(seqId: number, pane: 'primary' | 'secondary', timeoutMs = 120): Promise<void> {
    return new Promise((resolve, reject) => {
      const unsub = registerAckListener((msg) => {
        if (isStale(seqId)) { unsub(); reject(new StaleLaunchSequenceError()); return; }
        if (msg.type === 'session_ready' && msg.seqId === seqId && msg.pane === pane) {
          unsub();
          resolve();
        }
      });
      setTimeout(() => { unsub(); reject(new Error('session_timeout')); }, timeoutMs);
    });
  }

  // Helper function: wait for streams to commit with strict generation checks (THE CORNERSTONE PATCH)
  function waitForStreamsToCommit(
    seqId: number,
    hasSecondary: boolean,
    primaryStartGen: number,
    secondaryStartGen: number,
    timeoutMs = 150
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      const unsub = compositorStore.subscribe((state) => {
        if (isStale(seqId)) { unsub(); reject(new StaleLaunchSequenceError()); return; }

        const primary = state.viewports.get('primary');
        const secondary = state.viewports.get('secondary');

        const primaryReady = primary ? (primary.committed && primary.generation > primaryStartGen) : true;
        const secondaryReady = hasSecondary && secondary ? (secondary.committed && secondary.generation > secondaryStartGen) : true;

        if (primaryReady && secondaryReady) {
          unsub();
          resolve();
        }
      });

      setTimeout(() => {
        unsub();
        reject(new Error('stream_timeout'));
      }, timeoutMs);
    });
  }

  beforeEach(() => {
    ackListeners = [];
    currentSeqId = 0;
    
    // Reset compositorStore initial state
    compositorStore.set({
      viewports: new Map([
        ['primary', { pane: 'primary', width: 1280, height: 720, committed: false, generation: 0, visible: true }],
        ['secondary', { pane: 'secondary', width: 1280, height: 720, committed: false, generation: 0, visible: false }]
      ]),
      diagnostics: [],
      serverDiagnostics: null,
      layoutMode: 'split',
      splitRatio: 0.5,
      activePrimaryApp: '',
      activeSecondaryApp: '',
      popup: { visible: false, minimized: false, x: 0, y: 0, width: 0, height: 0 },
      launchSequence: {
        id: 0,
        primaryPkg: '',
        secondaryPkg: '',
        layoutMode: 'split',
        state: 'IDLE',
        startedAt: 0,
        primaryStartGen: 0,
        secondaryStartGen: 0,
      }
    });
  });

  it('Scenario 1: Should complete E2E ACK sequence and transition to RUNNING successfully', async () => {
    const seqId = nextLaunchSeqId();
    const primaryStartGen = 0;
    const secondaryStartGen = 0;

    compositorStore.update((curr) => ({
      ...curr,
      activePrimaryApp: 'com.android.settings',
      launchSequence: {
        id: seqId,
        primaryPkg: 'com.android.settings',
        layoutMode: 'split',
        state: 'LAYOUT_ALIGNING',
        startedAt: Date.now(),
        primaryStartGen,
        secondaryStartGen,
      }
    }));

    // Start simulated async checks
    const launchPromise = (async () => {
      // 1. Simulate Layout alignment
      compositorStore.update(state => ({ ...state, launchSequence: { ...state.launchSequence, state: 'LAYOUT_SENT' } }));
      await waitForLayoutAck(seqId, 'split');
      
      // 2. Simulate Primary Launch Command
      compositorStore.update(state => ({ ...state, launchSequence: { ...state.launchSequence, state: 'LAUNCHING_PRIMARY' } }));
      await waitForLaunchAck(seqId, 'primary');
      
      // 3. Simulate Primary Session Ready
      compositorStore.update(state => ({ ...state, launchSequence: { ...state.launchSequence, state: 'PRIMARY_SESSION_READY' } }));
      await waitForSessionReady(seqId, 'primary');
      
      // 4. Simulate Stream Commit Wait
      compositorStore.update(state => ({ ...state, launchSequence: { ...state.launchSequence, state: 'STREAM_COMMITTING' } }));
      await waitForStreamsToCommit(seqId, false, primaryStartGen, secondaryStartGen);

      // 5. Final transition to RUNNING steady-state
      compositorStore.update(state => ({
        ...state,
        launchSequence: { ...state.launchSequence, state: 'RUNNING', degradedReason: '' }
      }));
    })();

    // Simulate backend sending E2E packets asynchronously
    setTimeout(() => emitAckMessage({ type: 'layout_ack', seqId, success: true }), 10);
    setTimeout(() => emitAckMessage({ type: 'launch_ack', seqId, pane: 'primary', success: true }), 20);
    setTimeout(() => emitAckMessage({ type: 'session_ready', seqId, pane: 'primary' }), 30);
    
    // Simulate frontend receiving first frame and updating viewport generation and committed status
    setTimeout(() => {
      compositorStore.update((state) => {
        const viewports = new Map(state.viewports);
        viewports.set('primary', { pane: 'primary', width: 1280, height: 720, committed: true, generation: 1, visible: true });
        return { ...state, viewports };
      });
    }, 45);

    await expect(launchPromise).resolves.not.toThrow();
    expect(get(compositorStore).launchSequence.state).toBe('RUNNING');
    expect(get(compositorStore).launchSequence.degradedReason).toBe('');
  });

  it('Scenario 2: Should correctly intercept stream_timeout, catch it, and degrade to DEGRADED operation status', async () => {
    const seqId = nextLaunchSeqId();
    const primaryStartGen = 0;
    const secondaryStartGen = 0;
    let degradedReasonVal: 'launch_failure' | 'session_timeout' | 'stream_timeout' | '' = '';

    compositorStore.update((curr) => ({
      ...curr,
      activePrimaryApp: 'com.android.settings',
      launchSequence: {
        id: seqId,
        primaryPkg: 'com.android.settings',
        layoutMode: 'split',
        state: 'LAYOUT_ALIGNING',
        startedAt: Date.now(),
        primaryStartGen,
        secondaryStartGen,
      }
    }));

    const launchPromise = (async () => {
      try {
        await waitForLayoutAck(seqId, 'split');
        await waitForLaunchAck(seqId, 'primary');
        await waitForSessionReady(seqId, 'primary');

        // This stream commit will exceed timeout limit because we intentionally do NOT update viewport generation
        try {
          await waitForStreamsToCommit(seqId, false, primaryStartGen, secondaryStartGen, 30);
        } catch (err) {
          if (err instanceof StaleLaunchSequenceError) throw err;
          // Capture stream_timeout safely
          degradedReasonVal = 'stream_timeout';
        }

        const nextState = degradedReasonVal ? 'DEGRADED' : 'RUNNING';
        compositorStore.update(state => ({
          ...state,
          launchSequence: { ...state.launchSequence, state: nextState, degradedReason: degradedReasonVal }
        }));
      } catch (err) {
        compositorStore.update(state => ({
          ...state,
          launchSequence: { ...state.launchSequence, state: 'FAILED', error: err.message }
        }));
      }
    })();

    // Simulate backend packets but DO NOT trigger stream commit frame (generation remains 0)
    setTimeout(() => emitAckMessage({ type: 'layout_ack', seqId, success: true }), 5);
    setTimeout(() => emitAckMessage({ type: 'launch_ack', seqId, pane: 'primary', success: true }), 10);
    setTimeout(() => emitAckMessage({ type: 'session_ready', seqId, pane: 'primary' }), 15);

    await launchPromise;

    // Verify it resolved with DEGRADED state and stream_timeout reason instead of breaking with FAILED
    expect(get(compositorStore).launchSequence.state).toBe('DEGRADED');
    expect(get(compositorStore).launchSequence.degradedReason).toBe('stream_timeout');
  });

  it('Scenario 3: Should fail immediately to FAILED state if non-recoverable launch_failed ACK arrives', async () => {
    const seqId = nextLaunchSeqId();
    const primaryStartGen = 0;
    const secondaryStartGen = 0;
    let degradedReasonVal: 'launch_failure' | 'session_timeout' | 'stream_timeout' | '' = '';

    compositorStore.update((curr) => ({
      ...curr,
      activePrimaryApp: 'com.android.settings',
      launchSequence: {
        id: seqId,
        primaryPkg: 'com.android.settings',
        layoutMode: 'split',
        state: 'LAYOUT_ALIGNING',
        startedAt: Date.now(),
        primaryStartGen,
        secondaryStartGen,
      }
    }));

    const launchPromise = (async () => {
      try {
        await waitForLayoutAck(seqId, 'split');
        
        try {
          await waitForLaunchAck(seqId, 'primary', 100);
        } catch (err) {
          degradedReasonVal = 'launch_failure';
          throw err; // Non-recoverable activity startup fail -> escalates to master sequence catch
        }
        
        await waitForSessionReady(seqId, 'primary');
        await waitForStreamsToCommit(seqId, false, primaryStartGen, secondaryStartGen, 100);

        compositorStore.update(state => ({
          ...state,
          launchSequence: { ...state.launchSequence, state: 'RUNNING' }
        }));
      } catch (err) {
        compositorStore.update(state => ({
          ...state,
          launchSequence: { ...state.launchSequence, state: 'FAILED', error: err.message, degradedReason: degradedReasonVal }
        }));
      }
    })();

    // Simulate backend layout success, but launch failed explicitly
    setTimeout(() => emitAckMessage({ type: 'layout_ack', seqId, success: true }), 5);
    setTimeout(() => emitAckMessage({ type: 'launch_failed', seqId, pane: 'primary' }), 15);

    await launchPromise;

    // Verify the state machine reacted immediately by entering FAILED state with correct diagnostics reason
    expect(get(compositorStore).launchSequence.state).toBe('FAILED');
    expect(get(compositorStore).launchSequence.degradedReason).toBe('launch_failure');
  });
});
