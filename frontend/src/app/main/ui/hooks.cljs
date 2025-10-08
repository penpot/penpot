
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs S.L

(ns app.main.ui.hooks
  "A collection of general purpose react hooks."
  (:require
   [app.common.files.focus :as cpf]
   [app.common.math :as mth]
   [app.main.broadcast :as mbc]
   [app.main.data.shortcuts :as dsc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.storage :as storage]
   [app.util.timers :as ts]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [beicon.v2.operators :as rxo]
   [goog.functions :as f]
   [rumext.v2 :as mf]))

(def ^:private render-id 0)

(defn use-render-id
  "Get a stable, DOM usable identifier across all react rerenders"
  []
  (mf/useMemo #(js* "\"render-\" + (++~{})" render-id) #js []))

(defn use-rxsub
  [ob]
  (let [[state reset-state!] (mf/useState #(if (satisfies? IDeref ob) @ob nil))]
    (mf/useEffect
     (fn []
       (let [sub (rx/sub! ob #(reset-state! %))]
         #(rx/dispose! sub)))
     #js [ob])
    state))

(defn use-shortcuts
  [key shortcuts]
  (mf/use-effect
   #js [(str key) shortcuts]
   (fn []
     (st/emit! (dsc/push-shortcuts key shortcuts))
     (fn []
       (st/emit! (dsc/pop-shortcuts key))))))

(defn- set-timer
  [state ms func]
  (assoc state :timer (ts/schedule ms func)))

(defn- cancel-timer
  [state]
  (let [timer (:timer state)]
    (if timer
      (do
        (rx/dispose! timer)
        (dissoc state :timer))
      state)))

(def sortable-ctx (mf/create-context nil))

(mf/defc sortable-container*
  [{:keys [children]}]
  (let [global-drag-end (mf/use-memo #(rx/subject))]
    [:> (mf/provider sortable-ctx) {:value global-drag-end}
     children]))


;; The dnd API is problematic for nested elements, such a sortable items tree.
;; The approach used here to solve bad situations is:
;; - Capture all events in the leaf draggable elements, and stop propagation.
;; - Ignore events originated in non-draggable children.
;; - At drag operation end, all elements that have received some enter/over
;;   event and have not received the corresponding leave event, are notified
;;   so they can clean up. This can be occur, for example, if
;;    * some leave events are throttled out because of a slow computer
;;    * some corner cases of mouse entering a container element, and then
;;      moving into a contained element. This is anyway mitigated by not
;;      stopping propagation of leave event.
;;
;; Do not remove commented out lines, they are useful to debug events when
;; things go weird.

(defn use-sortable
  [& {:keys [data-type data on-drop on-drag on-hold disabled detect-center? draggable?]
      :or {draggable? true}
      :as opts}]
  (let [ref   (mf/use-ref)
        state (mf/use-state {:over nil
                             :timer nil
                             :subscr nil})

        global-drag-end (mf/use-ctx sortable-ctx)

        cleanup
        (fn []
          (some-> (:subscr @state) rx/dispose!)
          (swap! state (fn [state]
                         (-> state
                             (cancel-timer)
                             (dissoc :over :subscr)))))

        subscribe-to-drag-end
        (fn []
          (when (nil? (:subscr @state))
            (swap! state
                   #(assoc % :subscr (rx/sub! global-drag-end cleanup)))))

        on-drag-start
        (fn [event]
          (if (or disabled (not draggable?))
            (do
              (dom/stop-propagation event)
              (dom/prevent-default event))
            (do
              (dom/stop-propagation event)
              (dnd/set-data! event data-type data)
              (dnd/set-drag-image! event (dnd/invisible-image))
              (dnd/set-allowed-effect! event "move")
              (when (fn? on-drag)
                (on-drag data)))))

        on-drag-enter
        (fn [event]
          (dom/prevent-default event) ;; prevent default to allow drag enter
          (when-not (dnd/from-child? event)
            (dom/stop-propagation event)
            (subscribe-to-drag-end)
            ;; (dnd/trace event data "drag-enter")
            (when (fn? on-hold)
              (swap! state (fn [state]
                             (-> state
                                 (cancel-timer)
                                 (set-timer 1000 on-hold)))))))

        on-drag-over
        (fn [event]
          (when (dnd/has-type? event data-type)
            (dom/prevent-default event) ;; prevent default to allow drag over
            (when-not (dnd/from-child? event)
              (dom/stop-propagation event)
              (subscribe-to-drag-end)
              ;; (dnd/trace event data "drag-over")
              (let [side (dnd/drop-side event detect-center?)]
                (swap! state assoc :over side)))))

        on-drag-leave
        (fn [event]
          (when-not (dnd/from-child? event)
            ;; (dnd/trace event data "drag-leave")
            (cleanup)))

        on-drop'
        (fn [event]
          (dom/stop-propagation event)
          ;; (dnd/trace event data "drop")
          (let [side (dnd/drop-side event detect-center?)
                drop-data (dnd/get-data event data-type)]
            (cleanup)
            (rx/push! global-drag-end nil)
            (when (fn? on-drop)
              (on-drop side drop-data event))))

        on-drag-end
        (fn [event]
          (dom/stop-propagation event)
          ;; (dnd/trace event data "drag-end")
          (rx/push! global-drag-end nil)
          (cleanup))

        on-mount
        (fn []
          (let [dom (mf/ref-val ref)]
            ;; In firefox it needs to be draggable for problems with event handling.
            ;; It will stop the drag operation in on-drag-start
            (.setAttribute dom "draggable" (and draggable? (not disabled)))

            ;; Register all events in the (default) bubble mode, so that they
            ;; are captured by the most leaf item. The handler will stop
            ;; propagation, so they will not go up in the containment tree.
            (.addEventListener dom "dragstart" on-drag-start false)
            (.addEventListener dom "dragenter" on-drag-enter false)
            (.addEventListener dom "dragover" on-drag-over false)
            (.addEventListener dom "dragleave" on-drag-leave false)
            (.addEventListener dom "drop" on-drop' false)
            (.addEventListener dom "dragend" on-drag-end false)
            #(do
               (.removeEventListener dom "dragstart" on-drag-start)
               (.removeEventListener dom "dragenter" on-drag-enter)
               (.removeEventListener dom "dragover" on-drag-over)
               (.removeEventListener dom "dragleave" on-drag-leave)
               (.removeEventListener dom "drop" on-drop')
               (.removeEventListener dom "dragend" on-drag-end))))]

    (mf/use-effect
     (mf/deps data on-drop draggable?)
     on-mount)

    [(deref state) ref]))

(defn use-stream
  "Wraps the subscription to a stream into a `use-effect` call"
  ([stream on-subscribe]
   (use-stream stream (mf/deps) on-subscribe))
  ([stream deps on-subscribe]
   (use-stream stream deps on-subscribe nil))
  ([stream deps on-subscribe on-dispose]
   (mf/use-effect
    deps
    (fn []
      (let [sub (->> stream (rx/subs! on-subscribe))]
        #(do
           (rx/dispose! sub)
           (when on-dispose (on-dispose))))))))

;; https://reactjs.org/docs/hooks-faq.html#how-to-get-the-previous-props-or-state
;; FIXME: replace with rumext
(defn use-previous
  "Returns the value from previous render cycle."
  [value]
  (let [ref (mf/use-ref value)]
    (mf/use-effect
     (mf/deps value)
     (fn []
       (mf/set-ref-val! ref value)))
    (mf/ref-val ref)))

(defn use-update-var
  "Returns a var pointer what automatically updates with latest values."
  [value]
  (let [ptr (mf/use-var value)]
    (mf/with-effect [value]
      (reset! ptr value))
    ptr))

;; FIXME: replace with rumext
(defn use-update-ref
  [value]
  (let [ref (mf/use-ref value)]
    (mf/with-effect [value]
      (mf/set-ref-val! ref value))
    ref))

;; FIXME: replace with rumext
(defn use-ref-callback
  "Returns a stable callback pointer what calls the interned
  callback. The interned callback will be automatically updated on
  each render if the reference changes and works as noop if the
  pointer references to nil value."
  [f]
  (let [ptr (mf/use-ref nil)]
    (mf/with-effect [f]
      (mf/set-ref-val! ptr #js {:f f}))
    (mf/use-fn
     (fn [& args]
       (let [obj  (mf/ref-val ptr)]
         (when ^boolean obj
           (apply (.-f obj) args)))))))

;; FIXME: replace with rumext
(defn use-ref-value
  "Returns a ref that will be automatically updated when the value is changed"
  [v]
  (let [ref (mf/use-ref v)]
    (mf/with-effect [v]
      (mf/set-ref-val! ref v))
    ref))

;; FIXME: replace with rumext
(defn use-equal-memo
  [val]
  (let [ref (mf/use-ref nil)]
    (when-not (= (mf/ref-val ref) val)
      (mf/set-ref-val! ref val))
    (mf/ref-val ref)))

;; FIXME: rename to use-focus-objects
(defn with-focus-objects
  ([objects]
   (let [focus (mf/deref refs/workspace-focus-selected)]
     (with-focus-objects objects focus)))

  ([objects focus]
   (mf/with-memo [focus objects]
     (cpf/focus-objects objects focus))))

;; FIXME: replace with rumext
(defn use-debounce
  [ms value]
  (let [[state update-state-fn] (mf/useState value)
        update-fn (mf/use-memo (mf/deps ms) #(f/debounce update-state-fn ms))]
    (mf/with-effect [value]
      (update-fn value))
    state))

(defn use-shared-state
  "A specialized hook that adds persistence and inter-context reactivity
  to the default mf/use-state hook.

  The state is automatically persisted under the provided key on
  localStorage. And it will keep watching events with type equals to
  `key` for new values."
  [key default]
  (let [id     (mf/use-id)
        state* (mf/use-state #(get storage/user key default))
        state  (deref state*)
        stream (mf/with-memo [id]
                 (->> mbc/stream
                      (rx/filter #(not= (:id %) id))
                      (rx/filter #(= (:type %) key))
                      (rx/map deref)))]

    (mf/with-effect [state key id]
      (mbc/emit! id key state)
      (swap! storage/user assoc key state))

    (use-stream stream (partial reset! state*))

    state*))

(defn use-persisted-state
  "A specialized hook that adds persistence to the default mf/use-state hook.

  The state is automatically persisted under the provided key on
  localStorage. And it will keep watching events with type equals to
  `key` for new values."
  [key default]
  (let [id     (mf/use-id)
        state* (mf/use-state #(get storage/user key default))
        state  (deref state*)]

    (mf/with-effect [state key id]
      (swap! storage/user assoc key state))

    state*))

(defonce ^:private intersection-subject (rx/subject))
(defonce ^:private intersection-observer
  (delay (js/IntersectionObserver.
          (fn [entries _]
            (run! (partial rx/push! intersection-subject) (seq entries)))
          #js {:rootMargin "0px"
               :threshold #js [0 1.0]})))

(defn use-visible
  [ref & {:keys [once?]}]
  (let [state (mf/useState false)
        update-state! (aget state 1)
        state         (aget state 0)]

    (mf/with-effect [once?]
      (let [node   (mf/ref-val ref)
            stream (->> intersection-subject
                        (rx/filter (fn [entry]
                                     (let [target (unchecked-get entry "target")]
                                       (identical? target node))))
                        (rx/map (fn [entry]
                                  (let [ratio         (unchecked-get entry "intersectionRatio")
                                        intersecting? (unchecked-get entry "isIntersecting")
                                        intersecting? (or ^boolean intersecting?
                                                          ^boolean (> ratio 0.5))]
                                    (when (and (true? intersecting?) (true? once?))
                                      (.unobserve ^js @intersection-observer node))

                                    intersecting?)))

                        (rx/pipe (rxo/distinct-contiguous)))
            subs (rx/sub! stream update-state!)]
        (.observe ^js @intersection-observer node)
        (fn []
          (.unobserve ^js @intersection-observer node)
          (rx/dispose! subs))))

    state))

(defn use-dynamic-grid-item-width
  ([] (use-dynamic-grid-item-width nil))
  ([itemsize]
   (let [width*   (mf/use-state nil)
         width    (deref width*)

         rowref   (mf/use-ref)

         itemsize (cond
                    (some? itemsize) itemsize
                    (>= width 1030)  280
                    :else            230)

         ratio    (if (some? width) (/ width itemsize) 0)
         nitems   (mth/floor ratio)
         limit    (mth/min 10 nitems)
         limit    (mth/max 1 limit)

         th-size (when width
                   (mth/floor (- (/ (- width 32 (* (dec limit) 24)) limit) 12)))

         ;; Need an even value
         th-size (if (odd? (int th-size)) (- th-size 1) th-size)]

     (mf/with-effect
       [th-size]
       (when th-size
         (let [node (mf/ref-val rowref)]
           (.setProperty (.-style node) "--th-width" (str th-size "px"))
           (.setProperty (.-style node) "--th-height" (str (mth/ceil (* th-size (/ 2 3))) "px")))))

     (mf/with-effect []
       (let [node (mf/ref-val rowref)
             mnt? (volatile! true)
             sub  (->> (wapi/observe-resize node)
                       (rx/subs! (fn [entries]
                                   (let [row       (first entries)
                                         row-rect  (.-contentRect ^js row)
                                         row-width (.-width ^js row-rect)]
                                     (when @mnt?
                                       (reset! width* row-width))))))]
         (fn []
           (vreset! mnt? false)
           (rx/dispose! sub))))

     [rowref limit])))
