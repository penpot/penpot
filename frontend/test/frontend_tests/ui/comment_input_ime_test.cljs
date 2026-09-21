;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.comment-input-ime-test
  "Regression tests for https://github.com/penpot/penpot/issues/11757.

  Confirming a Japanese IME composition with Enter duplicated the text
  because the comment-input keydown handler treated every Enter as a
  Penpot line-break action. The component handlers delegate to
  `handle-comment-input-key-down` and `handle-thread-key-down`, which
  run nothing while the keydown belongs to an active IME composition
  (nativeEvent.isComposing or keyCode 229 as a fallback).

  These tests call the actual handler fns with stubbed dependencies
  and assert which side effects fire — not a resolver return value —
  so a guard moved to the wrong place, a reordered handle-select, or
  a handler bypassing the composition check fails here. The mention
  snapshot ordering matches the component: handle-select runs first
  (and may update the open mention) before the branch is read."
  (:require
   [app.main.ui.comments :as cmt]
   [cljs.test :as t :include-macros true]))

(defn- keydown-event
  "Build a synthetic keydown event shaped like the Rumext keyboard
  events the comment handlers receive. preventDefault/stopPropagation
  record into the returned log atom so tests observe real handler
  side effects on the event itself."
  [{:keys [key composing? key-code ctrl? meta? log]
    :or {composing? false ctrl? false meta? false}}]
  #js {:key key
       :keyCode key-code
       :ctrlKey ctrl?
       :metaKey meta?
       :preventDefault (fn [] (when log (swap! log conj :prevent-default)))
       :stopPropagation (fn [] (when log (swap! log conj :stop-propagation)))
       :nativeEvent #js {:isComposing composing?}})

(defn- enter
  ([] (enter {}))
  ([opts] (keydown-event (merge {:key "Enter" :key-code 13} opts))))

(defn- escape
  ([] (escape {}))
  ([opts] (keydown-event (merge {:key "Escape" :key-code 27} opts))))

(defn- arrow
  [dir opts]
  (keydown-event (merge {:key dir
                         :key-code (if (= dir "ArrowDown") 40 38)}
                        opts)))

(def ^:private composing {:composing? true})
(def ^:private ime229 {:composing? false :key-code 229})

(defn- input-deps
  "Stub dependency map for handle-comment-input-key-down. Every
  observable side effect appends to :log: mention-panel commands,
  delegated callbacks, select/input/newline/backspace paths, and the
  event defaults. :open-mention is read through :get-mention after
  :do-select runs, mirroring the component ordering."
  [{:keys [open-mention log select-updates-mention?]
    :or {log (atom [])}}]
  (let [mention (atom open-mention)]
    {:log log
     :deps {:get-mention (fn [] @mention)
            :push-mention! (fn [msg] (swap! log conj [:mention msg]))
            :on-esc (fn [_] (swap! log conj :on-esc))
            :on-ctrl-enter (fn [_] (swap! log conj :on-ctrl-enter))
            :do-select (fn [_]
                         (swap! log conj :select)
                         (when select-updates-mention?
                           (reset! mention select-updates-mention?)))
            :get-node (fn [] :node)
            :get-span (fn [_] [:span 3])
            :do-newline (fn [_] (swap! log conj :newline))
            :do-backspace (fn [_] (swap! log conj :backspace-check))
            :do-input (fn [] (swap! log conj :input))}}))

(defn- run-input!
  [event stub]
  (cmt/handle-comment-input-key-down event (:deps stub))
  @(:log stub))

(defn- run-thread!
  [event]
  (let [log (atom [])]
    (cmt/handle-thread-key-down event {:close! (fn [ev] (swap! log conj [:close ev]))})
    @log))

;; --- 1. composing Enter: zero Penpot side effects -----------------------

(t/deftest composing-enter-produces-zero-side-effects
  (t/testing "composing Enter fires nothing: no select, no mention, no newline, no event defaults"
    (let [log (atom [])
          events (run-input! (enter (merge composing {:log log}))
                             (input-deps {:log log}))]
      (t/is (= [] events))))
  (t/testing "composing Enter with a mention open still fires nothing"
    (let [log (atom [])
          events (run-input! (enter (merge composing {:log log}))
                             (input-deps {:log log :open-mention "@bob"}))]
      (t/is (= [] events)))))

;; --- 2. keyCode 229 fallback --------------------------------------------

(t/deftest keycode-229-enter-bypasses-custom-processing
  (t/testing "keyCode 229 Enter with isComposing=false fires nothing"
    (let [log (atom [])
          events (run-input! (enter (merge ime229 {:log log}))
                             (input-deps {:log log}))]
      (t/is (= [] events))))
  (t/testing "229 fallback also covers Escape and arrows"
    (let [log (atom [])]
      (t/is (= [] (run-input! (escape (merge ime229 {:log log}))
                              (input-deps {:log log}))))
      (t/is (= [] (run-input! (arrow "ArrowDown" (merge ime229 {:log log}))
                              (input-deps {:log log})))))))

;; --- 3. plain Enter regression ------------------------------------------

(t/deftest plain-enter-keeps-newline-behavior
  (t/testing "non-composing Enter runs select then the newline path"
    (let [log (atom [])
          events (run-input! (enter {:log log}) (input-deps {:log log}))]
      (t/is (= [:select :newline] events))))
  (t/testing "non-composing mod+Enter still calls on-ctrl-enter"
    (let [log (atom [])
          events (run-input! (enter {:log log :ctrl? true :meta? true})
                             (input-deps {:log log}))]
      (t/is (= [:select :on-ctrl-enter] events)))))

;; --- 4. composing Escape in the comment input ---------------------------

(t/deftest composing-escape-calls-no-input-command
  (t/testing "composing Escape fires nothing, even with on-esc wired"
    (let [log (atom [])
          events (run-input! (escape (merge composing {:log log}))
                             (input-deps {:log log}))]
      (t/is (= [] events))))
  (t/testing "composing Escape with a mention open emits no hide-mentions"
    (let [log (atom [])
          events (run-input! (escape (merge composing {:log log}))
                             (input-deps {:log log :open-mention "@bob"}))]
      (t/is (= [] events))))
  (t/testing "plain Escape still calls on-esc"
    (let [log (atom [])
          events (run-input! (escape {:log log}) (input-deps {:log log}))]
      (t/is (= [:select :on-esc] events)))))

;; --- 5. composing Escape in the floating thread -------------------------

(t/deftest floating-thread-escape-ownership
  (t/testing "composing Escape never closes the thread"
    (t/is (= [] (run-thread! (escape composing))))
    (t/is (= [] (run-thread! (escape ime229)))))
  (t/testing "plain Escape still closes the thread"
    (let [events (run-thread! (escape {}))]
      (t/is (= 1 (count events)))
      (t/is (= :close (ffirst events)))))
  (t/testing "non-Escape keys in the thread do nothing"
    (t/is (= [] (run-thread! (enter {}))))))

;; --- 6. mention candidate keyboard ownership ----------------------------

(t/deftest composing-mention-keys-trigger-no-mention-command
  (t/testing "composing mention keys fire nothing"
    (let [log (atom [])
          stub (input-deps {:log log :open-mention "@bob"})]
      (t/is (= [] (run-input! (enter (merge composing {:log log})) stub)))
      (t/is (= [] (run-input! (arrow "ArrowDown" (merge composing {:log log})) stub)))
      (t/is (= [] (run-input! (arrow "ArrowUp" (merge composing {:log log})) stub)))
      (t/is (= [] (run-input! (escape (merge composing {:log log})) stub)))))
  (t/testing "non-composing mention keys keep existing behavior"
    (let [log (atom [])
          events (run-input! (enter {:log log})
                             (input-deps {:log log :open-mention "@bob"}))]
      (t/is (= [:select
                :prevent-default
                :stop-propagation
                [:mention {:type :insert-selected-mention}]]
               events)))
    (let [log (atom [])
          events (run-input! (arrow "ArrowDown" {:log log})
                             (input-deps {:log log :open-mention "@bob"}))]
      (t/is (= [:select
                :prevent-default
                :stop-propagation
                [:mention {:type :insert-next-mention}]]
               events)))
    (let [log (atom [])
          events (run-input! (arrow "ArrowUp" {:log log})
                             (input-deps {:log log :open-mention "@bob"}))]
      (t/is (= [:select
                :prevent-default
                :stop-propagation
                [:mention {:type :insert-prev-mention}]]
               events)))
    (let [log (atom [])
          events (run-input! (escape {:log log})
                             (input-deps {:log log :open-mention "@bob"}))]
      (t/is (= [:select
                :prevent-default
                :stop-propagation
                [:mention {:type :hide-mentions}]]
               events)))))

;; --- ordering: select runs before the mention branch is read -----------

(t/deftest select-runs-before-mention-branch
  (t/testing "a mention opened by handle-select is visible to the branch"
    ;; Component ordering: do-select may set the open mention, and the
    ;; branch reads it afterwards. A snapshot taken before select
    ;; would miss it and wrongly fall through to the newline path.
    (let [log (atom [])
          events (run-input! (enter {:log log})
                             (input-deps {:log log
                                          :select-updates-mention? "@new"}))]
      (t/is (= [:select
                :prevent-default
                :stop-propagation
                [:mention {:type :insert-selected-mention}]]
               events)))))

;; --- backspace path preserved -------------------------------------------

(t/deftest plain-backspace-keeps-mention-check
  (t/testing "non-composing Backspace still runs the mention check path"
    (let [log (atom [])
          events (run-input! (keydown-event {:key "Backspace" :key-code 8 :log log})
                             (input-deps {:log log}))]
      (t/is (= [:select :backspace-check] events))))
  (t/testing "composing Backspace fires nothing"
    (let [log (atom [])
          events (run-input! (keydown-event {:key "Backspace" :key-code 8
                                             :composing? true :log log})
                             (input-deps {:log log}))]
      (t/is (= [] events)))))
