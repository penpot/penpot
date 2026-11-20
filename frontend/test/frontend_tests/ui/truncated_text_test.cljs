;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.truncated-text-test
  "`truncated-text*` shows a tooltip with the full text only while the
  text is cut. jsdom does no layout, so the tests set the widths the
  component reads and play the part of the browser's `ResizeObserver`."
  (:require
   ["jsdom" :refer [JSDOM]]
   ["react" :as react]
   ["react-dom/client" :as rdc]
   [app.main.ui.components.truncated-text :as tt]
   [app.main.ui.hooks :as hooks]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [rumext.v2 :as mf]))

(def ^:private long-name
  "DS_Feedback,Content&Tables_Components_Forms,Inputs&Validation_Patterns_Library_v2")

(t/deftest truncated-when-the-text-is-wider-than-its-box
  (t/is (true? (tt/truncated? #js {:scrollWidth 320 :clientWidth 200})))
  (t/is (false? (tt/truncated? #js {:scrollWidth 200 :clientWidth 200})))
  (t/is (false? (tt/truncated? #js {:scrollWidth 120 :clientWidth 200}))))

(defn- ^:async with-mounted-text
  "Mounts `truncated-text*` with `long-name` in a jsdom document whose
  elements report `widths` (an atom of `{:scroll n :client n}`), and
  calls `test-fn` with the container and a function that fires the
  resize observers, as the browser does when a box changes size."
  [widths test-fn]
  (let [dom          (JSDOM. "<!doctype html><html><body><div id='root'></div></body></html>")
        window       (.-window dom)
        document     (.-document window)
        container    (.getElementById document "root")
        proto        (.. window -HTMLElement -prototype)
        ;; Observed [node callback] pairs. Like the browser, a resize only
        ;; reaches the nodes that are still in the document.
        observed     (atom #{})
        old-window   (.-window js/globalThis)
        old-document (.-document js/globalThis)
        old-observer (.-ResizeObserver js/globalThis)
        old-act      (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)
        resize       (fn []
                       (doseq [[node callback] @observed]
                         (when (.-isConnected node)
                           (callback))))]
    (js/Object.defineProperty proto "scrollWidth" #js {:get #(:scroll @widths)})
    (js/Object.defineProperty proto "clientWidth" #js {:get #(:client @widths)})
    (set! (.-window js/globalThis) window)
    (set! (.-document js/globalThis) document)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (set! (.-ResizeObserver js/globalThis)
          (fn [callback]
            (this-as this
                     (let [nodes (atom #{})]
                       (set! (.-observe this)
                             (fn [node]
                               (swap! nodes conj node)
                               (swap! observed conj [node callback])))
                       (set! (.-disconnect this)
                             (fn []
                               (swap! observed #(reduce disj % (map (fn [n] [n callback]) @nodes))))))
                     this)))
    (try
      (await
       (mock/with-mocks* {hooks/use-portal-container (fn ([] (.-body document))
                                                       ([_] (.-body document)))}
         (let [root (rdc/createRoot container)]
           (try
             (await (react/act
                     #(.render root (mf/element tt/truncated-text*
                                                #js {:text long-name :as "div"}))))
             (await (test-fn container (fn [] (react/act resize))))
             (finally
               (await (react/act #(.unmount root))))))))
      (finally
        (.close window)
        (set! (.-window js/globalThis) old-window)
        (set! (.-document js/globalThis) old-document)
        (set! (.-ResizeObserver js/globalThis) old-observer)
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) old-act)))))

(defn- tooltip-trigger
  "The element the tooltip listens on: it carries the full text as its
  accessible name."
  [container]
  (.querySelector container (str "[aria-label=\"" long-name "\"]")))

(t/deftest ^:async text-that-fits-has-no-tooltip
  (await
   (with-mounted-text (atom {:scroll 200 :client 200})
     (fn [container _]
       (t/is (= long-name (.-textContent container)))
       (t/is (nil? (tooltip-trigger container)))))))

(t/deftest ^:async cut-text-has-a-tooltip-with-the-full-text
  (await
   (with-mounted-text (atom {:scroll 320 :client 200})
     (fn [container _]
       (t/is (= long-name (.-textContent container)))
       (t/is (some? (tooltip-trigger container)))))))

(t/deftest ^:async tooltip-follows-the-size-of-the-box
  ;; Wrapping the text in the tooltip mounts a new node: the observer
  ;; must follow it, or the tooltip would stay after the box grows again.
  (let [widths (atom {:scroll 200 :client 200})]
    (await
     (with-mounted-text widths
       (^:async fn [container resize]
         (t/is (nil? (tooltip-trigger container)))

         (reset! widths {:scroll 320 :client 200})
         (await (resize))
         (t/is (some? (tooltip-trigger container)))

         (reset! widths {:scroll 320 :client 400})
         (await (resize))
         (t/is (nil? (tooltip-trigger container))))))))
