;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.comment-input-ime-test
  "Keydown handling of the comment input and floating thread: keys owned
  by an IME composition run nothing, all other keys keep their
  commands."
  (:require
   [app.main.ui.comments :as cmt]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]))

(defn- keydown
  "Fake keyboard event. preventDefault/stopPropagation append to `log`."
  [key {:keys [composing? key-code mod? log]}]
  #js {:key key
       :keyCode (or key-code 0)
       :ctrlKey (boolean mod?)
       :metaKey (boolean mod?)
       :preventDefault #(some-> log (swap! conj :prevent-default))
       :stopPropagation #(some-> log (swap! conj :stop-propagation))
       :nativeEvent #js {:isComposing (boolean composing?)}})

(def ^:private composition-modes
  {"isComposing" {:composing? true}
   "keyCode 229" {:key-code 229}})

(def ^:private input-keys
  ["Enter" "Escape" "ArrowDown" "ArrowUp" "Backspace"])

(defn- run-input!
  "Runs the comment-input handler for `key` and returns the side-effect
  log. Mention-panel commands are observed on a real subject and the
  open mention lives in an atom, as in the component."
  [key {:keys [event-opts mention select-sets-mention node span on-esc? on-ctrl-enter?]
        :or   {node :node span [:span 3] on-esc? true on-ctrl-enter? true}}]
  (let [log         (atom [])
        cur-mention (atom mention)
        mentions-s  (rx/subject)
        sub         (rx/sub! mentions-s #(swap! log conj [:mention (:type %)]))
        event       (keydown key (assoc event-opts :log log))]
    (cmt/handle-comment-input-key-down
     event
     {:node          node
      :cur-mention   cur-mention
      :mentions-s    mentions-s
      :on-select     (fn [e]
                       (t/is (identical? event e))
                       (swap! log conj :select)
                       (some->> select-sets-mention (reset! cur-mention)))
      :get-span      (fn [n]
                       (t/is (= node n))
                       span)
      :on-esc        (when on-esc? #(swap! log conj :on-esc))
      :on-ctrl-enter (when on-ctrl-enter? #(swap! log conj :on-ctrl-enter))
      :on-newline    #(swap! log conj [:newline (dissoc % :event)])
      :on-backspace  #(swap! log conj [:backspace (dissoc % :event)])})
    (rx/dispose! sub)
    @log))

(defn- run-thread!
  [key event-opts]
  (let [log (atom [])]
    (cmt/handle-thread-key-down (keydown key event-opts)
                                #(swap! log conj :close))
    @log))

(def ^:private ctx {:node :node :span-node :span :offset 3})

(t/deftest composing-keys-run-nothing
  (doseq [[mode opts] composition-modes
          key         input-keys
          mention     [nil "@bob"]]
    (t/testing (str key " via " mode ", mention " (pr-str mention))
      (t/is (= [] (run-input! key {:event-opts opts :mention mention}))))))

(t/deftest plain-keys-keep-their-commands
  (t/testing "Enter inserts a line break at the caret span"
    (t/is (= [:select [:newline ctx]] (run-input! "Enter" {}))))
  (t/testing "mod+Enter submits"
    (t/is (= [:select :on-ctrl-enter]
             (run-input! "Enter" {:event-opts {:mod? true}}))))
  (t/testing "mod+Enter without on-ctrl-enter falls back to a line break"
    (t/is (= [:select [:newline ctx]]
             (run-input! "Enter" {:event-opts {:mod? true} :on-ctrl-enter? false}))))
  (t/testing "Escape calls on-esc"
    (t/is (= [:select :on-esc] (run-input! "Escape" {}))))
  (t/testing "Escape without on-esc does nothing else"
    (t/is (= [:select] (run-input! "Escape" {:on-esc? false}))))
  (t/testing "Backspace runs the mention-deletion check"
    (t/is (= [:select [:backspace ctx]] (run-input! "Backspace" {}))))
  (t/testing "other keys only sync the selection"
    (t/is (= [:select] (run-input! "a" {})))))

(t/deftest open-mention-routes-panel-keys
  (doseq [[key cmd] {"Enter"     :insert-selected-mention
                     "ArrowDown" :insert-next-mention
                     "ArrowUp"   :insert-prev-mention
                     "Escape"    :hide-mentions}]
    (t/testing key
      (t/is (= [:select :prevent-default :stop-propagation [:mention cmd]]
               (run-input! key {:mention "@bob"})))))
  (t/testing "Backspace is not a panel key"
    (t/is (= [:select [:backspace ctx]]
             (run-input! "Backspace" {:mention "@bob"})))))

(t/deftest mention-is-read-after-select
  (t/testing "a mention opened by on-select routes the same key"
    (t/is (= [:select :prevent-default :stop-propagation
              [:mention :insert-selected-mention]]
             (run-input! "Enter" {:select-sets-mention "@new"})))))

(t/deftest missing-caret-target-runs-only-select
  (t/testing "no input node"
    (t/is (= [:select] (run-input! "Enter" {:node nil}))))
  (t/testing "caret outside a text span"
    (t/is (= [:select] (run-input! "Enter" {:span nil})))))

(t/deftest floating-thread-escape
  (doseq [[mode opts] composition-modes]
    (t/testing (str "Escape via " mode " keeps the thread open")
      (t/is (= [] (run-thread! "Escape" opts)))))
  (t/testing "plain Escape closes the thread"
    (t/is (= [:close] (run-thread! "Escape" {}))))
  (t/testing "other keys do nothing"
    (t/is (= [] (run-thread! "Enter" {})))))
