;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.ds-popover-test
  "The DS popover closes on a click out of it and out of its container,
  and on Escape; it ignores the click that opened it, and renders
  nothing while closed. The document comes from jsdom, since the runner
  has none."
  (:require
   ["jsdom" :refer [JSDOM]]
   ["react" :as react]
   ["react-dom/client" :as rdc]
   [app.main.ui.ds.layout.popover :refer [popover*]]
   [app.util.globals :as globals]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [rumext.v2 :as mf]))

(mf/defc host*
  "A bar (the container), the popover with a button inside, and a spot
  out of both."
  {::mf/private true}
  [{:keys [is-open on-close]}]
  (let [bar-ref (mf/use-ref nil)]
    [:div
     [:div {:id "bar" :ref bar-ref} "bar"]
     [:> popover* {:is-open is-open :on-close on-close :container bar-ref}
      [:button {:id "inside"} "inside"]]
     [:div {:id "outside"} "outside"]]))

(defn- later
  "Resolves after the timers queued so far, the one that lets the
  popover listen among them."
  []
  (js/Promise. (fn [resolve] (js/setTimeout resolve 10))))

(defn- ^:async with-host
  "Mounts `host*` open (`is-open`) in a jsdom document and calls
  `test-fn` with a fn that finds an element by id, a fn that sends a
  keyup of `key` to the document, and the number of times the popover
  asked to close (an atom)."
  [is-open test-fn]
  (let [dom          (JSDOM. "<!doctype html><html><body><div id='root'></div></body></html>")
        window       (.-window dom)
        document     (.-document window)
        old-window   (.-window js/globalThis)
        old-document (.-document js/globalThis)
        old-act      (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)
        closes       (atom 0)
        on-close     #(swap! closes inc)
        by-id        #(.getElementById document %)
        key-up       (fn [key]
                       (.dispatchEvent document
                                       (new (.-KeyboardEvent window) "keyup"
                                            #js {:key key :bubbles true})))]
    (set! (.-window js/globalThis) window)
    (set! (.-document js/globalThis) document)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (try
      (await
       (mock/with-mocks* {globals/document document globals/window window}
         (let [root (rdc/createRoot (by-id "root"))]
           ;; a synchronous act runs the effects before it returns, and
           ;; nothing waits after it: the popover is set up and its
           ;; timer has not run yet, as right after the click that
           ;; opened it
           (react/act #(.render root (mf/element host* #js {:isOpen is-open
                                                            :onClose on-close})))
           (await (test-fn by-id key-up closes))
           (await (react/act #(.unmount root))))))
      (finally
        (set! (.-window js/globalThis) old-window)
        (set! (.-document js/globalThis) old-document)
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) old-act)))))

(t/deftest ^:async a-closed-popover-renders-nothing
  (await
   (with-host false
     (^:async fn [by-id _ _]
       (t/is (nil? (by-id "inside")))
       (t/is (some? (by-id "outside")))))))

(t/deftest ^:async a-click-out-of-it-closes-it
  (await
   (with-host true
     (^:async fn [by-id _ closes]
       (await (later))
       (.click (by-id "outside"))
       (t/is (= 1 @closes))))))

(t/deftest ^:async a-click-in-it-or-in-its-container-keeps-it-open
  (await
   (with-host true
     (^:async fn [by-id _ closes]
       (await (later))
       (.click (by-id "inside"))
       (.click (by-id "bar"))
       (t/is (= 0 @closes))))))

(t/deftest ^:async escape-closes-it
  (await
   (with-host true
     (^:async fn [_ key-up closes]
       (await (later))
       (key-up "Enter")
       (t/is (= 0 @closes) "another key does nothing")
       (key-up "Escape")
       (t/is (= 1 @closes))))))

(t/deftest ^:async the-click-that-opens-it-does-not-close-it
  (await
   (with-host true
     (^:async fn [by-id _ closes]
       ;; before the popover listens, as the click that opened it
       (.click (by-id "outside"))
       (t/is (= 0 @closes))))))
