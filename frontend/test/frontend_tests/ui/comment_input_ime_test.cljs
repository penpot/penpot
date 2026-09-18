;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.comment-input-ime-test
  "Regression tests for https://github.com/penpot/penpot/issues/11757.

  Confirming a Japanese IME composition with Enter duplicated the text
  because the comment-input keydown handler treated every Enter as a
  Penpot line-break action. Both the comment-input handler and the
  parent floating-thread handler now resolve keydowns through
  `resolve-comment-key-action`, which returns :ime-owned while the
  keydown belongs to an active IME composition (nativeEvent.isComposing
  or keyCode 229, mirroring the v3 text-editor precedent from #10498).

  These tests verify the observable keyboard behavior — which Penpot
  command (if any) a keydown triggers — rather than the predicate
  alone, so a guard moved to the wrong place or a handler bypassing
  the resolver fails here."
  (:require
   [app.main.ui.comments :as cmt]
   [cljs.test :as t :include-macros true]))

(defn- keydown-event
  "Build a synthetic keydown event map shaped like the Rumext keyboard
  events the comment handlers receive."
  [{:keys [key composing? key-code ctrl? meta?]
    :or {composing? false ctrl? false meta? false}}]
  #js {:key key
       :keyCode key-code
       :ctrlKey ctrl?
       :metaKey meta?
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

(def ^:private no-ctx
  {:mention-open? false :has-on-esc? false :has-on-ctrl-enter? false})

;; --- 1. composing Enter: no Penpot newline ------------------------------

(t/deftest composing-enter-produces-no-penpot-newline
  (t/testing "composing Enter (isComposing) resolves to :ime-owned, not :newline"
    (t/is (= :ime-owned
             (cmt/resolve-comment-key-action (enter composing) no-ctx))))
  (t/testing "handler must not preventDefault/stopPropagation an IME-owned key"
    ;; The handlers only call dom/prevent-default / dom/stop-propagation
    ;; inside non-:ime-owned branches, so :ime-owned means the IME keeps
    ;; full ownership of the event.
    (t/is (= :ime-owned
             (cmt/resolve-comment-key-action (enter composing) no-ctx)))))

;; --- 2. keyCode 229 fallback --------------------------------------------

(t/deftest keycode-229-enter-bypasses-custom-processing
  (t/testing "keyCode 229 Enter with isComposing=false still resolves :ime-owned"
    (t/is (= :ime-owned
             (cmt/resolve-comment-key-action (enter ime229) no-ctx))))
  (t/testing "229 fallback also covers other IME-owned keys"
    (t/is (= :ime-owned
             (cmt/resolve-comment-key-action (escape ime229) no-ctx)))
    (t/is (= :ime-owned
             (cmt/resolve-comment-key-action (arrow "ArrowDown" ime229) no-ctx)))))

;; --- 3. plain Enter regression ------------------------------------------

(t/deftest plain-enter-keeps-newline-behavior
  (t/testing "non-composing Enter still resolves to the newline path"
    (t/is (= :newline
             (cmt/resolve-comment-key-action (enter) no-ctx))))
  (t/testing "non-composing mod+Enter still resolves to on-ctrl-enter"
    (t/is (= :on-ctrl-enter
             (cmt/resolve-comment-key-action
              (enter {:ctrl? true :meta? true})
              (assoc no-ctx :has-on-ctrl-enter? true))))))

;; --- 4. composing Escape in the comment input ---------------------------

(t/deftest composing-escape-calls-no-input-command
  (t/testing "composing Escape never resolves to on-esc"
    (t/is (= :ime-owned
             (cmt/resolve-comment-key-action
              (escape composing)
              (assoc no-ctx :has-on-esc? true)))))
  (t/testing "composing Escape never resolves to hide-mentions, even with a mention open"
    (t/is (= :ime-owned
             (cmt/resolve-comment-key-action
              (escape composing)
              (assoc no-ctx :mention-open? true)))))
  (t/testing "plain Escape still resolves to on-esc"
    (t/is (= :on-esc
             (cmt/resolve-comment-key-action
              (escape)
              (assoc no-ctx :has-on-esc? true))))))

;; --- 5. composing Escape in the floating thread -------------------------

(t/deftest floating-thread-escape-ownership
  (t/testing "composing Escape never resolves to close-thread"
    (t/is (= :ime-owned
             (cmt/resolve-comment-key-action (escape composing) {:thread? true})))
    (t/is (= :ime-owned
             (cmt/resolve-comment-key-action (escape ime229) {:thread? true}))))
  (t/testing "plain Escape still resolves to close-thread"
    (t/is (= :close-thread
             (cmt/resolve-comment-key-action (escape) {:thread? true}))))
  (t/testing "non-Escape keys in the thread resolve to :none"
    (t/is (= :none
             (cmt/resolve-comment-key-action (enter) {:thread? true})))))

;; --- 6. mention candidate keyboard ownership ----------------------------

(t/deftest composing-mention-keys-trigger-no-mention-command
  (let [mention-ctx (assoc no-ctx :mention-open? true)]
    (t/testing "composing Enter does not select a mention"
      (t/is (= :ime-owned
               (cmt/resolve-comment-key-action (enter composing) mention-ctx))))
    (t/testing "composing ArrowDown does not navigate mentions"
      (t/is (= :ime-owned
               (cmt/resolve-comment-key-action
                (arrow "ArrowDown" composing) mention-ctx))))
    (t/testing "composing ArrowUp does not navigate mentions"
      (t/is (= :ime-owned
               (cmt/resolve-comment-key-action
                (arrow "ArrowUp" composing) mention-ctx))))
    (t/testing "composing Escape does not dismiss mentions"
      (t/is (= :ime-owned
               (cmt/resolve-comment-key-action (escape composing) mention-ctx)))))
  (let [mention-ctx (assoc no-ctx :mention-open? true)]
    (t/testing "non-composing mention keys keep existing behavior"
      (t/is (= :insert-selected-mention
               (cmt/resolve-comment-key-action (enter) mention-ctx)))
      (t/is (= :insert-next-mention
               (cmt/resolve-comment-key-action (arrow "ArrowDown" {}) mention-ctx)))
      (t/is (= :insert-prev-mention
               (cmt/resolve-comment-key-action (arrow "ArrowUp" {}) mention-ctx)))
      (t/is (= :hide-mentions
               (cmt/resolve-comment-key-action (escape) mention-ctx))))))

;; --- backspace path preserved -------------------------------------------

(t/deftest plain-backspace-keeps-mention-check
  (t/testing "non-composing Backspace still resolves to the mention check path"
    (t/is (= :backspace-mention-check
             (cmt/resolve-comment-key-action
              (keydown-event {:key "Backspace" :key-code 8}) no-ctx))))
  (t/testing "composing Backspace resolves to :ime-owned"
    (t/is (= :ime-owned
             (cmt/resolve-comment-key-action
              (keydown-event {:key "Backspace" :key-code 8 :composing? true})
              no-ctx)))))
