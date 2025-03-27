;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.hooks.resize
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.logging :as log]
   [app.common.math :as mth]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.globals :as glob]
   [app.util.storage :as storage]
   [rumext.v2 :as mf]))

(log/set-level! :warn)

(def last-resize-type nil)

(defn- get-initial-state
  [initial file-id key]
  (let [saved (dm/get-in storage/user [::state file-id key])]
    (d/nilv saved initial)))

(defn- update-persistent-state
  [data file-id key size]
  (update-in data [::state file-id] assoc key size))

(defn set-resize-type! [type]
  (set! last-resize-type type))

(defn use-resize-hook
  "Allows a node to be resized by dragging, and calculates the new size based on the drag setting a maximum and minimum value.

   Parameters:
   - `key` - A unique key to identify the resize hook.
   - `initial` - The initial size of the node.
   - `min-val` - The minimum value the size can be.
   - `max-val` - The maximum value the size can be. It can be a number or a string representing either a fixed value or a percentage (0 to 1).
   - `axis` - The axis to resize on, either `:x` or `:y`.
   - `negate?` - If `true`, the axis is negated.
   - `resize-type` - The type of resize, either `:width` or `:height`.
   - `on-change-size` - A function to call when the size changes.

   Returns:
   - An object with the following:

     - `:on-pointer-down` - A function to call when the pointer is pressed down.
     - `:on-lost-pointer-capture` - A function to call when the pointer is released.
     - `:on-pointer-move` - A function to call when the pointer is moved.
     - `:parent-ref` - A reference to the node.
     - `:set-size` - A function to set the size.
     - `:size` - The current size."

  ([key initial min-val max-val axis negate? resize-type]
   (use-resize-hook key initial min-val max-val axis negate? resize-type nil))

  ([key initial min-val max-val axis negate? resize-type on-change-size]
   (let [file-id        (mf/use-ctx ctx/current-file-id)

         current-size*  (mf/use-state #(get-initial-state initial file-id key))
         current-size   (deref current-size*)

         parent-ref     (mf/use-ref nil)
         dragging-ref   (mf/use-ref false)
         start-size-ref (mf/use-ref nil)
         start-ref      (mf/use-ref nil)

        ;;  Since Penpot is not responsive designed, this value will only refer to vertical axis.
         window-height* (mf/use-state #(dom/get-window-height))
         window-height (deref window-height*)

        ;;  In case max-val is a string, we need to parse it as a double.
         max-val (mf/with-memo [max-val window-height]
                   (let [parsed-max-val (when (string? max-val) (d/parse-double max-val))]
                     (if parsed-max-val
                       (* window-height parsed-max-val)
                       max-val)))

         set-size
         (mf/use-fn
          (mf/deps file-id key min-val max-val window-height)
          (fn [new-size]
            (let [new-size (mth/clamp new-size min-val max-val)]
              (reset! current-size* new-size)
              ;; Save the new size in the local storage for this file and this specific node.
              (swap! storage/user update-persistent-state file-id key new-size))))

         on-pointer-down
         (mf/use-fn
          (mf/deps current-size)
          (fn [event]
            (dom/capture-pointer event)
            (mf/set-ref-val! start-size-ref current-size)
            (mf/set-ref-val! dragging-ref true)
            (mf/set-ref-val! start-ref (dom/get-client-position event))
            (set! last-resize-type resize-type)))

         on-lost-pointer-capture
         (mf/use-fn
          (fn [event]
            (dom/release-pointer event)
            (mf/set-ref-val! start-size-ref nil)
            (mf/set-ref-val! dragging-ref false)
            (mf/set-ref-val! start-ref nil)
            (set! last-resize-type nil)))

         on-pointer-move
         (mf/use-fn
          (mf/deps min-val max-val negate? file-id key)
          (fn [event]
            (when (mf/ref-val dragging-ref)
              (let [start (mf/ref-val start-ref)
                    pos   (dom/get-client-position event)
                    delta (-> (gpt/to-vec start pos)
                              (cond-> negate? gpt/negate)
                              (get axis))

                    start-size (mf/ref-val start-size-ref)

                    new-size (-> (+ start-size delta) (max min-val) (min max-val))]

                (set-size new-size)))))

         on-resize-window
         (mf/use-fn
          (fn []
            (let [new-window-height (dom/get-window-height)]
              (reset! window-height* new-window-height))))]

     (mf/with-effect [on-change-size current-size]
       (when on-change-size
         (on-change-size current-size)))

     (mf/with-effect []
       (.addEventListener glob/window "resize" on-resize-window)
       (fn []
         (.removeEventListener glob/window "resize" on-resize-window)))

     (mf/with-effect [window-height]
       (let [new-size (mth/clamp current-size min-val max-val)]
         (set-size new-size)))

     {:on-pointer-down on-pointer-down
      :on-lost-pointer-capture on-lost-pointer-capture
      :on-pointer-move on-pointer-move
      :parent-ref parent-ref
      :set-size set-size
      :size current-size})))

(defn use-resize-observer
  [callback]

  (dm/assert!
   "expected a valid callback"
   (fn? callback))

  (let [prev-val-ref (mf/use-ref nil)
        observer-ref (mf/use-ref nil)
        callback     (hooks/use-ref-callback callback)

        ;; We use the ref as a callback when the dom node is ready (or change)
        node-ref
        (mf/use-fn
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
                   (.observe observer node)
                   (callback last-resize-type (dom/get-client-size node)))))

             (mf/set-ref-val! prev-val-ref node))))]

    (mf/with-layout-effect []
      ;; On dismount we need to disconnect the current observer
      (fn []
        (when-let [observer (mf/ref-val observer-ref)]
          (log/debug :action "disconnect")
          (.disconnect ^js observer))))

    node-ref))
