; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.viewport.hooks
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.focus :as cpf]
   [app.common.files.helpers :as cfh]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.types.component :as ctk]
   [app.common.types.shape-tree :as ctt]
   [app.common.uuid :as uuid]
   [app.main.data.shortcuts :as dsc]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.grid-layout.shortcuts :as gsc]
   [app.main.data.workspace.path.shortcuts :as psc]
   [app.main.data.workspace.shortcuts :as wsc]
   [app.main.data.workspace.text.shortcuts :as tsc]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.shapes.frame :as frame]
   [app.main.ui.workspace.shapes.frame.dynamic-modifiers :as sfd]
   [app.main.ui.workspace.viewport.actions :as actions]
   [app.main.ui.workspace.viewport.utils :as utils]
   [app.main.worker :as mw]
   [app.render-wasm.api :as wasm.api]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.keyboard :as kbd]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [beicon.v2.operators :as rxo]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(defn setup-dom-events
  [zoom disable-paste-ref in-viewport-ref workspace-read-only? drawing-tool drawing-path?]
  (let [zoom-ref          (mf/use-ref zoom)
        _                 (mf/with-effect [zoom]
                            (mf/set-ref-val! zoom-ref zoom))
        on-key-down       (actions/on-key-down)
        on-key-up         (actions/on-key-up)
        on-mouse-wheel    (actions/on-mouse-wheel zoom-ref)
        on-paste          (actions/on-paste disable-paste-ref in-viewport-ref workspace-read-only?)
        on-pointer-down   (mf/use-fn
                           (fn [e]
                             (when-not (dom/get-parent-with-data (dom/get-target e) "dont-clear-path")
                               (st/emit! (dwe/clear-edition-mode)))))
        on-blur           (mf/use-fn #(st/emit! (mse/->BlurEvent)))]

    (mf/with-effect [drawing-tool drawing-path?]
      (mf/set-ref-val! disable-paste-ref (= drawing-tool :comments)))

    (mf/with-effect [drawing-path? on-pointer-down]
      (when drawing-path?
        (.addEventListener js/window "pointerdown" on-pointer-down)
        #(.removeEventListener js/window "pointerdown" on-pointer-down)))

    ;; The camera uses client coordinates. Closure's BrowserEvent also reads
    ;; offsetX/Y, which can force layout before the first zoom or pan update.
    (mf/with-layout-effect [on-mouse-wheel]
      (.addEventListener js/window "wheel" on-mouse-wheel #js {:passive false})
      #(.removeEventListener js/window "wheel" on-mouse-wheel))

    (mf/with-layout-effect [on-key-down on-key-up on-paste workspace-read-only?]
      (let [keys [(events/listen js/document EventType.KEYDOWN on-key-down)
                  (events/listen js/document EventType.KEYUP on-key-up)
                  (events/listen js/window EventType.PASTE on-paste)
                  (events/listen js/window EventType.BLUR on-blur)]]
        (fn []
          (doseq [key keys]
            (events/unlistenByKey key)))))))

(defn setup-viewport-size [vport viewport-ref]
  (mf/with-effect [vport]
    (let [node (mf/ref-val viewport-ref)
          prnt (dom/get-parent node)
          size (dom/get-client-size prnt)]

      (when (not= size vport)
        (st/emit! (dw/initialize-viewport (dom/get-client-size prnt)))))))

(defn setup-cursor [cursor alt? mod? space? panning drawing-tool drawing-path? path-editing? path-drag-cursor z? workspace-read-only? can-alt-duplicate?]
  (mf/use-effect
   (mf/deps @cursor @alt? @mod? @space? panning drawing-tool drawing-path? path-editing? path-drag-cursor z? workspace-read-only? can-alt-duplicate?)
   (fn []
     (let [show-pen? (or (= drawing-tool :path)
                         (and drawing-path?
                              (not= drawing-tool :curve)))
           show-zoom? (and @z?
                           (not @space?)
                           (not @mod?)
                           (not drawing-path?)
                           (not drawing-tool))

           new-cursor
           (cond
             (and @mod? @space?)             (utils/get-cursor :zoom)
             (or panning @space?)            (utils/get-cursor :hand)
             ;; Keep the drag cursor across the viewport.
             (some? path-drag-cursor)        (cur/get-static path-drag-cursor)
             (= drawing-tool :comments)      (utils/get-cursor :comments)
             (= drawing-tool :frame)         (utils/get-cursor :create-artboard)
             (= drawing-tool :rect)          (utils/get-cursor :create-rectangle)
             (= drawing-tool :circle)        (utils/get-cursor :create-ellipse)
             (and show-zoom? (not @alt?))    (utils/get-cursor :zoom-in)
             (and show-zoom? @alt?)          (utils/get-cursor :zoom-out)
             show-pen?                       (utils/get-cursor :draw-path)
             (= drawing-tool :curve)         (utils/get-cursor :pencil)
             drawing-tool                    (utils/get-cursor :create-shape)
             path-editing?                   (utils/get-cursor :edit-path)
             (and
              @alt?
              (not workspace-read-only?)
              can-alt-duplicate?)            (utils/get-cursor :duplicate)
             :else                           (utils/get-cursor :pointer-inner))]

       (when (not= @cursor new-cursor)
         (reset! cursor new-cursor))))))

(defn setup-keyboard
  [alt* mod* space* z* shift*]
  (let [kbd-zoom-s
        (mf/with-memo []
          (->> ms/keyboard
               (rx/filter kbd/key-down-event?)
               (rx/filter kbd/mod-event?)
               (rx/filter (fn [kevent]
                            (or ^boolean (kbd/minus? kevent)
                                ^boolean (kbd/underscore? kevent)
                                ^boolean (kbd/equals? kevent)
                                ^boolean (kbd/plus? kevent))))
               (rx/pipe (rxo/distinct-contiguous))))

        kbd-shift-s
        (mf/with-memo []
          (->> ms/keyboard
               (rx/filter kbd/shift-key?)
               (rx/filter (complement kbd/editing-event?))
               (rx/map kbd/key-down-event?)
               (rx/pipe (rxo/distinct-contiguous))))

        kbd-z-s
        (mf/with-memo []
          (->> ms/keyboard
               (rx/filter kbd/z?)
               (rx/filter (complement kbd/editing-event?))
               (rx/map kbd/key-down-event?)
               (rx/pipe (rxo/distinct-contiguous))))]

    (hooks/use-stream ms/keyboard-alt (partial reset! alt*))
    (hooks/use-stream ms/keyboard-space (partial reset! space*))
    (hooks/use-stream kbd-z-s (partial reset! z*))
    (hooks/use-stream kbd-shift-s (partial reset! shift*))
    (hooks/use-stream ms/keyboard-mod
                      (fn [value]
                        (reset! mod* value)
                        ;; In mac after command+z there is no event
                        ;; for the release of the z key
                        (when-not ^boolean value
                          (reset! z* false))))
    (hooks/use-stream kbd-zoom-s
                      (fn [kevent]
                        (dom/prevent-default kevent)
                        (st/emit!
                         (if (or ^boolean (kbd/minus? kevent)
                                 ^boolean (kbd/underscore? kevent))
                           (dw/decrease-zoom)
                           (dw/increase-zoom)))))))

(defn setup-hover-shapes
  [page-id move-stream pick-stream objects selected hover measure-hover hover-ids hover-top-frame-id hover-disabled? focus zoom show-measures? read-only? transform]
  (let [;; We use ref so we don't recreate the stream on a change
        zoom-ref (mf/use-ref zoom)
        selected-ref (mf/use-ref selected)
        hover-disabled-ref (mf/use-ref hover-disabled?)
        focus-ref (mf/use-ref focus)
        transform-ref (mf/use-ref transform)
        read-only-ref (mf/use-ref read-only?)

        last-point-ref (mf/use-var nil)
        last-modifier-hit (mf/use-var nil)
        last-hover-mod? (mf/use-var false)

        query-point
        (mf/use-callback
         (mf/deps page-id)
         (fn [point mod? pick]
           (let [zoom (mf/ref-val zoom-ref)
                 ;; A single modifier transition changes which layers can be
                 ;; hit at a stationary pointer. It must survive the worker's
                 ;; coalescing of motion messages for the preceding mode.
                 enter-mod? (and mod? (not pick) (not @last-hover-mod?))
                 _ (when-not pick (reset! last-hover-mod? mod?))
                 query {:cmd :index/query-selection
                        :page-id page-id
                        :rect (grc/center->rect point (/ 5 zoom))
                        :include-frames? true
                        :clip-children? (not mod?)
                        :using-selrect? false}
                 revision @mw/selection-index-revision
                 hit @last-modifier-hit
                 ids (cond
                       (mf/ref-val hover-disabled-ref)
                       (rx/of [])

                       ;; Only reuse raw spatial hits for the exact query. The
                       ;; current selection, focus and layer rules still apply.
                       (and pick (= revision (:revision hit)) (= query (:query hit)))
                       (rx/of (:ids hit))

                       pick
                       (mw/ask! query)

                       mod?
                       (->> (if enter-mod? (mw/ask! query) (mw/ask-buffered! query))
                            (rx/tap (fn [ids]
                                      (when (some? ids)
                                        (reset! last-modifier-hit {:query query :revision revision :ids ids})))))

                       :else
                       (mw/ask-buffered! query))]
             (->> ids
                  (rx/filter some?)
                  (rx/map (fn [ids] [ids point mod? pick]))))))

        over-shapes-stream
        (mf/with-memo [query-point move-stream]
          (->> move-stream
               (rx/tap #(reset! last-point-ref %))
               (rx/combine-latest-with ms/keyboard-mod)
               (rx/switch-map (fn [[point mod?]] (query-point point mod? nil)))
               (rx/share)))

        shape-results
        (mf/with-memo [query-point over-shapes-stream pick-stream]
          (let [stopper (->> st/stream
                             (rx/filter #(or (= % :interrupt)
                                             (mse/blur-event? %)
                                             (and (mse/mouse-event? %)
                                                  (or (mse/mouse-down-event? %)
                                                      (mse/mouse-double-click-event? %)))
                                             (and (kbd/keyboard-event? %) (kbd/key-down-event? %)))))]
            (rx/merge over-shapes-stream
                      (->> pick-stream
                           (rx/switch-map (fn [{:keys [point] :as pick}]
                                            (->> (query-point point true pick)
                                                 (rx/take-until stopper))))))))

        over-shapes-stream-debounced
        (->> over-shapes-stream (rx/debounce 50))]

    ;; Refresh the refs on a value change
    (mf/use-effect
     (mf/deps zoom)
     #(mf/set-ref-val! zoom-ref zoom))

    (mf/use-effect
     (mf/deps selected)
     #(mf/set-ref-val! selected-ref selected))

    (mf/use-effect
     (mf/deps hover-disabled?)
     #(mf/set-ref-val! hover-disabled-ref hover-disabled?))

    (mf/use-effect
     (mf/deps focus)
     #(mf/set-ref-val! focus-ref focus))

    (mf/use-effect
     (mf/deps transform)
     #(mf/set-ref-val! transform-ref transform))

    (mf/use-effect
     (mf/deps read-only?)
     #(mf/set-ref-val! read-only-ref read-only?))

    (hooks/use-stream
     over-shapes-stream-debounced
     (mf/deps objects)
     (fn [_]
       (reset! hover-top-frame-id (ctt/top-nested-frame objects (deref last-point-ref) nil (mf/ref-val read-only-ref)))))

    ;; This ref is a cache of sorted ids. Sorting is expensive so we save the list
    (let [sorted-ids-cache (mf/use-ref {})]
      (hooks/use-stream
       shape-results
       (mf/deps page-id objects show-measures? read-only?)
       (fn [[ids point mod? pick]]
         (let [selected   (mf/ref-val selected-ref)
               focus      (mf/ref-val focus-ref)
               cached-ids (mf/ref-val sorted-ids-cache)

               make-sorted-ids
               (fn [mod? ids]
                 (let [sorted-ids
                       (into (d/ordered-set)
                             (comp (remove (partial cfh/hidden-parent? objects))
                                   (remove #(and (not read-only?) (dm/get-in objects [% :blocked])))
                                   (remove (partial cfh/svg-raw-shape? objects)))
                             (ctt/sort-z-index objects ids {:bottom-frames? mod?}))]
                   (mf/set-ref-val! sorted-ids-cache (assoc cached-ids [mod? ids] sorted-ids))
                   sorted-ids))

               ids (or (get cached-ids [mod? ids]) (make-sorted-ids mod? ids))

               grouped?
               (fn [id]
                 (and (cfh/group-like-shape? objects id)
                      (not (cfh/mask-shape? objects id))))

               selected-with-parents
               (into #{} (mapcat #(cfh/get-parent-ids objects %)) selected)

               root-frame-with-data?
               #(as-> (get objects %) obj
                  (and (cfh/root-frame? obj)
                       (d/not-empty? (:shapes obj))
                       (not (ctk/instance-head? obj))
                       (not (ctk/main-instance? obj))))

               ;; Set with the elements to remove from the hover list
               remove-hover-xf
               (cond
                 mod?
                 (filter grouped?)

                 (not mod?)
                 (let [child-parent?
                       (into #{}
                             (comp (remove #(cfh/group-shape? objects %))
                                   (mapcat #(cfh/get-parent-ids objects %)))
                             ids)]
                   (filter #(or (root-frame-with-data? %)
                                (and (cfh/group-shape? objects %)
                                     (not (contains? child-parent? %)))
                                ;; WASM only: drop text from @hover unless the
                                ;; cursor is over rendered glyphs, so clicks
                                ;; pass through empty areas of the text box.
                                ;; Skip this for shapes already in `selected`:
                                ;; @hover drives on-click, and the first click
                                ;; of a double-click would otherwise reselect
                                ;; a parent/container after the pointer moves.
                                (and (features/active-feature? @st/state "render-wasm/v1")
                                     (cfh/text-shape? (get objects %))
                                     (not (contains? selected %))
                                     (not (wasm.api/intersect-position-in-shape % point)))))))

               remove-measure-xf
               (cond
                 mod?
                 (filter grouped?)

                 (not mod?)
                 (let [child-parent?
                       (into #{}
                             (comp (remove #(cfh/group-like-shape? objects %))
                                   (mapcat #(cfh/get-parent-ids objects %)))
                             ids)]
                   (filter #(and (cfh/group-shape? objects %)
                                 (not (contains? child-parent? %))))))

               remove-hover?
               (into selected-with-parents remove-hover-xf ids)

               remove-measure?
               (into selected-with-parents remove-measure-xf ids)

               no-fill-nested-frames?
               (fn [id]
                 (let [shape (get objects id)]
                   (and (cfh/frame-shape? shape)
                        (not (cfh/is-direct-child-of-root? shape))
                        (empty? (get shape :fills)))))

               hover-shape
               (->> ids
                    (remove remove-hover?)
                    (remove #(and mod? (no-fill-nested-frames? %)))
                    (filter #(or (empty? focus) (cpf/is-in-focus? objects focus %)))
                    (first)
                    (get objects))

               ;; We keep track of a diferent shape for measures
               measure-hover-shape
               (when show-measures?
                 (->> ids
                      (remove remove-measure?)
                      (remove #(and mod? (no-fill-nested-frames? %)))
                      (filter #(or (empty? focus) (cpf/is-in-focus? objects focus %)))
                      (first)
                      (get objects)))]
           (if pick
             (when-let [id (:id hover-shape)]
               (st/emit! (dw/select-shape id (:shift? pick))))
             (do
               (reset! hover hover-shape)
               (reset! measure-hover measure-hover-shape)
               ;; Skip hover-ids update during drag
               (when (not= :move (mf/ref-val transform-ref))
                 (reset! hover-ids ids))))))

       (fn []
         ;; Clean the cache
         (mf/set-ref-val! sorted-ids-cache {}))))))

(defn setup-viewport-modifiers
  [modifiers objects]
  (let [root-frame-ids
        (mf/use-memo
         (mf/deps objects)
         #(ctt/get-root-shapes-ids objects))
        modifiers (select-keys modifiers (conj root-frame-ids uuid/zero))]
    (sfd/use-dynamic-modifiers objects globals/document modifiers)))

(defn inside-vbox [vbox objects frame-id]
  (let [frame (get objects frame-id)]
    (and (some? frame) (some? vbox) (gsh/overlaps? frame vbox))))

(defn setup-active-frames
  [objects hover-ids selected active-frames zoom transform vbox use-overviews?]

  (let [all-frames             (mf/use-memo (mf/deps objects) #(ctt/get-root-frames-ids objects))
        selected-frames        (mf/use-memo (mf/deps selected) #(->> all-frames (filter selected)))

        xf-selected-frame      (comp (remove cfh/root-frame?)
                                     (map #(cfh/get-shape-id-root-frame objects %)))

        selected-shapes-frames (mf/use-memo (mf/deps selected) #(into #{} xf-selected-frame selected))

        active-selection       (when (and (not= transform :move) (= (count selected-frames) 1)) (first selected-frames))
        last-hover-ids       (mf/use-var nil)]

    (mf/use-effect
     (mf/deps @hover-ids)
     (fn []
       (when (d/not-empty? @hover-ids)
         (reset! last-hover-ids (set @hover-ids)))))

    (mf/use-effect
     (mf/deps objects @hover-ids selected zoom transform vbox use-overviews?)
     (fn []

       ;; Rules for active frame:
       ;; A loaded, oversampled bitmap can also back selection outlines.
       ;; Entering a transform or an editing tool re-enables the live SVG.
       ;; - When zoom > 130% we activate the frames that are inside the vbox
       ;; - If no hovering over any frames we keep the previous active one
       ;; - Check always that the active frames are inside the vbox

       (let [hover-ids? (set (->> @hover-ids (map #(cfh/get-shape-id-root-frame objects %))))

             is-active-frame?
             (fn [id]
               (or
                ;; Zoom > 130% shows every frame
                (> zoom 1.3)

                (and
                 (or (and (or (= :move transform) (>= zoom 0.25))
                          (or (contains? hover-ids? id) (contains? @last-hover-ids id)))
                     (= id active-selection)
                     (contains? selected-shapes-frames id))
                 (not (and use-overviews? (frame/sharp-overview? (get objects id) zoom))))))

             new-active-frames
             (into #{}
                   (comp (filter is-active-frame?)

                         ;; We only allow active frames that are contained in the vbox
                         (filter (partial inside-vbox vbox objects)))
                   all-frames)

             ;; Debug only: Disable the thumbnails
             new-active-frames
             (cond
               (dbg/enabled? :disable-frame-thumbnails)
               (into #{} all-frames)

               (dbg/enabled? :force-frame-thumbnails)
               #{}

               :else
               new-active-frames)]

         (when (not= @active-frames new-active-frames)
           (reset! active-frames new-active-frames)))))))

;; NOTE: this is executed on each page change, maybe we need to move
;; this shortcuts outside the viewport?

(defn setup-shortcuts
  [path-editing? drawing-path? text-editing? grid-editing?]
  (hooks/use-shortcuts ::workspace wsc/shortcuts :workspace)

  (mf/with-effect []
    (.addEventListener js/window "keydown" wsc/on-display-guides-keydown)
    (fn []
      (.removeEventListener js/window "keydown" wsc/on-display-guides-keydown)))

  (mf/with-effect [path-editing? drawing-path? grid-editing?]
    (cond
      grid-editing?
      (do
        (st/emit! (dsc/push-shortcuts ::grid gsc/shortcuts :workspace
                                      :merge-shortcuts :auto))
        (fn []
          (st/emit! (dsc/pop-shortcuts ::grid))))

      (or drawing-path? path-editing?)
      (do
        (st/emit! (dsc/push-shortcuts ::path psc/shortcuts :workspace
                                      :merge-shortcuts :auto))
        (fn []
          (st/emit! (dsc/pop-shortcuts ::path))))

      text-editing?
      (do
        (st/emit! (dsc/push-shortcuts ::text tsc/shortcuts :workspace))
        (fn []
          (st/emit! (dsc/pop-shortcuts ::text)))))))
