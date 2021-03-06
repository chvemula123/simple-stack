/*
 * Copyright 2017 Gabor Varadi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zhuinden.simplestack;

import android.content.Context;
import android.os.Parcelable;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static java.lang.annotation.RetentionPolicy.SOURCE;

/**
 * The class that manages the active state record of the application.
 *
 * Created by Zhuinden on 2017. 01. 12..
 */
public class Backstack {
    public static <T extends Parcelable> T getKey(Context context) {
        return KeyContextWrapper.getKey(context);
    }

    //
    @Retention(SOURCE)
    @IntDef({INITIALIZE, REATTACH})
    private @interface StateChangerRegisterMode {
    }

    public static final int INITIALIZE = 0;
    public static final int REATTACH = 1;
    //

    private final List<Parcelable> originalStack = new ArrayList<>();

    private final List<Parcelable> initialParameters;
    private List<Parcelable> stack = originalStack;

    private LinkedList<PendingStateChange> queuedStateChanges = new LinkedList<>();

    private StateChanger stateChanger;

    public Backstack(@NonNull Parcelable... initialKeys) {
        if(initialKeys == null || initialKeys.length <= 0) {
            throw new IllegalArgumentException("At least one initial key must be defined");
        }
        initialParameters = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(initialKeys)));
    }

    public Backstack(@NonNull List<Parcelable> initialKeys) {
        if(initialKeys == null) {
            throw new NullPointerException("Initial key list should not be null");
        }
        if(initialKeys.size() <= 0) {
            throw new IllegalArgumentException("Initial key list should contain at least one element");
        }
        initialParameters = Collections.unmodifiableList(new ArrayList<>(initialKeys));
    }

    public boolean hasStateChanger() {
        return stateChanger != null;
    }

    public void setStateChanger(@NonNull StateChanger stateChanger, @StateChangerRegisterMode int registerMode) {
        if(stateChanger == null) {
            throw new NullPointerException("New state changer cannot be null");
        }
        this.stateChanger = stateChanger;
        if(registerMode == INITIALIZE && (queuedStateChanges.size() <= 1 || stack.isEmpty())) {
            if(!beginStateChangeIfPossible()) {
                ArrayList<Parcelable> newHistory = new ArrayList<>();
                newHistory.addAll(selectActiveHistory());
                stack = initialParameters;
                enqueueStateChange(newHistory, StateChange.REPLACE, true);
            }
            return;
        }
        beginStateChangeIfPossible();
    }

    public void removeStateChanger() {
        this.stateChanger = null;
    }

    public void goTo(@NonNull Parcelable newKey) {
        checkNewKey(newKey);

        ArrayList<Parcelable> newHistory = new ArrayList<>();
        boolean isNewKey = true;
        for(Parcelable key : selectActiveHistory()) {
            newHistory.add(key);
            if(key.equals(newKey)) {
                isNewKey = false;
                break;
            }
        }
        int direction;
        if(isNewKey) {
            newHistory.add(newKey);
            direction = StateChange.FORWARD;
        } else {
            direction = StateChange.BACKWARD;
        }
        enqueueStateChange(newHistory, direction, false);
    }

    public boolean goBack() {
        if(isStateChangePending()) {
            return true;
        }
        if(stack.size() <= 1) {
            stack.clear();
            return false;
        }
        ArrayList<Parcelable> newHistory = new ArrayList<>();

        List<Parcelable> activeHistory = selectActiveHistory();
        for(int i = 0; i < activeHistory.size() - 1; i++) {
            newHistory.add(activeHistory.get(i));
        }
        enqueueStateChange(newHistory, StateChange.BACKWARD, false);
        return true;
    }

    public void setHistory(@NonNull List<Parcelable> newHistory, @StateChange.StateChangeDirection int direction) {
        checkNewHistory(newHistory);
        enqueueStateChange(newHistory, direction, false);
    }

    public List<Parcelable> getHistory() {
        List<Parcelable> copy = new ArrayList<>();
        copy.addAll(stack);
        return Collections.unmodifiableList(copy);
    }

    public boolean isStateChangePending() {
        return !queuedStateChanges.isEmpty();
    }

    private void enqueueStateChange(List<Parcelable> newHistory, int direction, boolean initialization) {
        PendingStateChange pendingStateChange = new PendingStateChange(newHistory, direction, initialization);
        queuedStateChanges.add(pendingStateChange);
        beginStateChangeIfPossible();
    }

    private List<Parcelable> selectActiveHistory() {
        if(stack.isEmpty() && queuedStateChanges.size() <= 0) {
            return initialParameters;
        } else if(queuedStateChanges.size() <= 0) {
            return stack;
        } else {
            return queuedStateChanges.getLast().newHistory;
        }
    }

    private boolean beginStateChangeIfPossible() {
        if(hasStateChanger() && isStateChangePending()) {
            PendingStateChange pendingStateChange = queuedStateChanges.getFirst();
            if(pendingStateChange.getStatus() == PendingStateChange.Status.ENQUEUED) {
                pendingStateChange.setStatus(PendingStateChange.Status.IN_PROGRESS);
                changeState(pendingStateChange);
                return true;
            }
        }
        return false;
    }

    private void changeState(final PendingStateChange pendingStateChange) {
        boolean initialization = pendingStateChange.initialization;
        List<Parcelable> newHistory = pendingStateChange.newHistory;
        @StateChange.StateChangeDirection int direction = pendingStateChange.direction;

        List<Parcelable> previousState;
        if(initialization) {
            previousState = Collections.emptyList();
        } else {
            previousState = new ArrayList<>();
            previousState.addAll(stack);
        }
        final StateChange stateChange = new StateChange(Collections.unmodifiableList(previousState),
                Collections.unmodifiableList(newHistory),
                direction);
        StateChanger.Callback completionCallback = new StateChanger.Callback() {
            @Override
            public void stateChangeComplete() {
                if(!pendingStateChange.didForceExecute) {
                    if(pendingStateChange.getStatus() == PendingStateChange.Status.COMPLETED) {
                        throw new IllegalStateException("State change completion cannot be called multiple times!");
                    }
                    completeStateChange(stateChange);
                }
            }
        };
        pendingStateChange.completionCallback = completionCallback;
        stateChanger.handleStateChange(stateChange, completionCallback);
    }

    private void completeStateChange(StateChange stateChange) {
        if(initialParameters == stack) {
            stack = originalStack;
        }
        stack.clear();
        stack.addAll(stateChange.newState);

        PendingStateChange pendingStateChange = queuedStateChanges.removeFirst();
        pendingStateChange.setStatus(PendingStateChange.Status.COMPLETED);
        notifyCompletionListeners(stateChange);
        beginStateChangeIfPossible();
    }

    // completion listeners
    public interface CompletionListener {
        void stateChangeCompleted(@NonNull StateChange stateChange);
    }

    private LinkedList<CompletionListener> completionListeners = new LinkedList<>();

    public void addCompletionListener(@NonNull CompletionListener completionListener) {
        if(completionListener == null) {
            throw new IllegalArgumentException("Null completion listener cannot be added!");
        }
        completionListeners.add(completionListener);
    }

    public void removeCompletionListener(@NonNull CompletionListener completionListener) {
        if(completionListener == null) {
            throw new IllegalArgumentException("Null completion listener cannot be removed!");
        }
        completionListeners.remove(completionListener);
    }

    private void notifyCompletionListeners(StateChange stateChange) {
        for(CompletionListener completionListener : completionListeners) {
            completionListener.stateChangeCompleted(stateChange);
        }
    }

    // force execute
    public void executePendingStateChange() {
        if(isStateChangePending()) {
            PendingStateChange pendingStateChange = queuedStateChanges.getFirst();
            if(pendingStateChange.getStatus() == PendingStateChange.Status.IN_PROGRESS) {
                pendingStateChange.completionCallback.stateChangeComplete();
                pendingStateChange.didForceExecute = true;
            }
        }
    }

    // argument checks
    private void checkNewHistory(List<Parcelable> newHistory) {
        if(newHistory == null || newHistory.isEmpty()) {
            throw new IllegalArgumentException("New history cannot be null or empty");
        }
    }

    private void checkNewKey(Parcelable newKey) {
        if(newKey == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
    }
}
