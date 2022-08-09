;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.hooks.resize
  (:require
   [app.common.geom.point :as gpt]
   [app.common.logging :as log]
   [app.common.spec :as us]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.storage :refer [storage]]
   [rumext.alpha :as mf]))

(log/set-level! :warn)

(def last-resize-type nil)

(defn set-resize-type! [type]
  (set! last-resize-type type))

(defn use-resize-hook
  [key initial min-val max-val axis negate? resize-type]

  (let [current-file-id (mf/use-ctx ctx/current-file-id)
        size-state (mf/use-state (or (get-in @storage [::saved-resize current-file-id key]) initial))
        parent-ref (mf/use-ref nil)

        dragging-ref (mf/use-ref false)
        start-size-ref (mf/use-ref nil)
        start-ref (mf/use-ref nil)

        on-pointer-down
        (mf/use-callback
         (mf/deps @size-state)
         (fn [event]
           (dom/capture-pointer event)
           (mf/set-ref-val! start-size-ref @size-state)
           (mf/set-ref-val! dragging-ref true)
           (mf/set-ref-val! start-ref (dom/get-client-position event))
           (set! last-resize-type resize-type)))

        on-lost-pointer-capture
        (mf/use-callback
         (fn [event]
           (dom/release-pointer event)
           (mf/set-ref-val! start-size-ref nil)
           (mf/set-ref-val! dragging-ref false)
           (mf/set-ref-val! start-ref nil)
           (set! last-resize-type nil)))

        on-mouse-move
        (mf/use-callback
         (mf/deps min-val max-val)
         (fn [event]
           (when (mf/ref-val dragging-ref)
             (let [start (mf/ref-val start-ref)
                   pos (dom/get-client-position event)
                   delta (-> (gpt/to-vec start pos)
                             (cond-> negate? gpt/negate)
                             (get axis))
                   start-size (mf/ref-val start-size-ref)
                   new-size (-> (+ start-size delta) (max min-val) (min max-val))]
               (reset! size-state new-size)
               (swap! storage assoc-in [::saved-resize current-file-id key] new-size)))))]
    {:on-pointer-down on-pointer-down
     :on-lost-pointer-capture on-lost-pointer-capture
     :on-mouse-move on-mouse-move
     :parent-ref parent-ref
     :size @size-state}))

(defn use-resize-observer
  [callback]
  (us/assert! (some? callback) "the `callback` is mandatory")

  (let [prev-val-ref (mf/use-ref nil)
        observer-ref (mf/use-ref nil)
        callback     (hooks/use-ref-callback callback)

        ;; We use the ref as a callback when the dom node is ready (or change)
        node-ref     (mf/use-fn
                      (fn [^js node]
                        (when (some? node)
                          (let [^js observer (mf/ref-val observer-ref)
                                ^js prev-val (mf/ref-val prev-val-ref)]

                            (when (and (not= prev-val node) (some? observer))
                              (log/debug :action "disconnect" :js/prev-val prev-val :js/node node)
                              (.disconnect observer)
                              (mf/set-ref-val! observer-ref nil))

                            (when (and (not= prev-val node) (some? node))
                              (let [^js observer (js/ResizeObserver.
                                                  #(callback last-resize-type (dom/get-client-size node)))]
                                (mf/set-ref-val! observer-ref observer)
                                (log/debug :action "observe"  :js/node node :js/observer observer)
                                (.observe observer node))))

                          (mf/set-ref-val! prev-val-ref node))))]

    (mf/with-effect []
      ;; On dismount we need to disconnect the current observer
      (fn []
        (when-let [observer (mf/ref-val observer-ref)]
          (log/debug :action "disconnect")
          (.disconnect ^js observer))))

    node-ref))
