; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.actions
  (:require
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.drawing :as dd]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.path :as dwdp]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.workspace.viewport.utils :as utils]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.timers :as timers]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [rumext.alpha :as mf])
  (:import goog.events.WheelEvent))

(defn on-mouse-down
  [{:keys [id blocked hidden type]} selected edition drawing-tool text-editing?
   node-editing? drawing-path? create-comment? space? viewport-ref zoom panning]
  (mf/use-callback
   (mf/deps id blocked hidden type selected edition drawing-tool text-editing?
            node-editing? drawing-path? create-comment? @space? viewport-ref zoom
            panning)
   (fn [bevent]
     (when (or (dom/class? (dom/get-target bevent) "viewport-controls")
               (dom/class? (dom/get-target bevent) "viewport-selrect"))
       (dom/stop-propagation bevent)

       (let [event  (.-nativeEvent bevent)
             ctrl?  (kbd/ctrl? event)
             meta?  (kbd/meta? event)
             shift? (kbd/shift? event)
             alt?   (kbd/alt? event)
             mod?   (kbd/mod? event)

             left-click?   (and (not panning) (= 1 (.-which event)))
             middle-click? (and (not panning) (= 2 (.-which event)))

             frame? (= :frame type)
             selected? (contains? selected id)]

         (cond
           middle-click?
           (do
             (dom/prevent-default bevent)
             (if mod?
               (let [raw-pt   (dom/get-client-position event)
                     viewport (mf/ref-val viewport-ref)
                     pt       (utils/translate-point-to-viewport viewport zoom raw-pt)]
                 (st/emit! (dw/start-zooming pt)))
               (st/emit! (dw/start-panning))))


           left-click?
           (do
             (st/emit! (ms/->MouseEvent :down ctrl? shift? alt? meta?))

             (when (and (not= edition id) text-editing?)
               (st/emit! dw/clear-edition-mode))

             (when (and (not text-editing?)
                        (not blocked)
                        (not hidden)
                        (not create-comment?)
                        (not drawing-path?))
               (cond
                 node-editing?
                 ;; Handle path node area selection
                 (st/emit! (dwdp/handle-area-selection shift?))

                 (and @space? mod?)
                 (let [raw-pt   (dom/get-client-position event)
                       viewport (mf/ref-val viewport-ref)
                       pt       (utils/translate-point-to-viewport viewport zoom raw-pt)]
                   (st/emit! (dw/start-zooming pt)))

                 @space?
                 (st/emit! (dw/start-panning))

                 drawing-tool
                 (st/emit! (dd/start-drawing drawing-tool))

                 (or (not id) (and frame? (not selected?)) mod?)
                 (st/emit! (dw/handle-area-selection shift? mod?))

                 (not drawing-tool)
                 (st/emit! (dw/start-move-selected id shift?)))))))))))

(defn on-move-selected
  [hover hover-ids selected space?]
  (mf/use-callback
   (mf/deps @hover @hover-ids selected @space?)
   (fn [bevent]
     (let [event (.-nativeEvent bevent)
           shift? (kbd/shift? event)
           mod?   (kbd/mod? event)
           left-click?   (= 1 (.-which event))]

       (when (and left-click?
                  (not mod?)
                  (not shift?)
                  (not @space?))
         (dom/prevent-default bevent)
         (dom/stop-propagation bevent)
         (st/emit! (dw/start-move-selected)))))))

(defn on-frame-select
  [selected]
  (mf/use-callback
   (mf/deps selected)
   (fn [event id]
     (let [shift? (kbd/shift? event)
           selected? (contains? selected id)]
       (st/emit! (when (or shift? (not selected?))
                   (dw/select-shape id shift?))
                 (when (not shift?)
                   (dw/start-move-selected)))))))

(defn on-frame-enter
  [frame-hover]
  (mf/use-callback
   (fn [id]
     (reset! frame-hover id))))

(defn on-frame-leave
  [frame-hover]
  (mf/use-callback
   (fn []
     (reset! frame-hover nil))))

(defn on-click
  [hover selected edition drawing-path? drawing-tool space? selrect]
  (mf/use-callback
   (mf/deps @hover selected edition drawing-path? drawing-tool @space? selrect)
   (fn [event]
     (when (and (nil? selrect)
                (or (dom/class? (dom/get-target event) "viewport-controls")
                    (dom/class? (dom/get-target event) "viewport-selrect")))
       (let [ctrl? (kbd/ctrl? event)
             shift? (kbd/shift? event)
             alt? (kbd/alt? event)
             meta? (kbd/meta? event)
             mod? (kbd/mod? event)
             hovering? (some? @hover)
             frame? (= :frame (:type @hover))]
         (st/emit! (ms/->MouseEvent :click ctrl? shift? alt? meta?))

         (when (and hovering?
                    (or (not frame?) mod?)
                    (not @space?)
                    (not edition)
                    (not drawing-path?)
                    (not drawing-tool))
           (st/emit! (dw/select-shape (:id @hover) shift?))))))))

(defn on-double-click
  [hover hover-ids drawing-path? objects edition]
  (mf/use-callback
   (mf/deps @hover @hover-ids drawing-path? edition)
   (fn [event]
     (dom/stop-propagation event)
     (let [ctrl? (kbd/ctrl? event)
           shift? (kbd/shift? event)
           alt? (kbd/alt? event)
           meta? (kbd/meta? event)

           {:keys [id type] :as shape} @hover

           frame? (= :frame type)
           group? (= :group type)]

       (st/emit! (ms/->MouseEvent :double-click ctrl? shift? alt? meta?))

       ;; Emit asynchronously so the double click to exit shapes won't break
       (timers/schedule
        #(when (and (not drawing-path?) shape)
           (cond
             frame?
             (st/emit! (dw/select-shape id shift?))

             (and group? (> (count @hover-ids) 1))
             (let [selected (get objects (second @hover-ids))]
               (reset! hover selected)
               (reset! hover-ids (into [] (rest @hover-ids)))

               (st/emit! (dw/select-shape (:id selected))))

             (not= id edition)
             (st/emit! (dw/select-shape id)
                       (dw/start-editing-selected)))))))))

(defn on-context-menu
  [hover hover-ids]
  (mf/use-callback
   (mf/deps @hover @hover-ids)
   (fn [event]
     (when (or (dom/class? (dom/get-target event) "viewport-controls")
               (dom/class? (dom/get-target event) "viewport-selrect"))
       (dom/prevent-default event)

       (let [position (dom/get-client-position event)]
         ;; Delayed callback because we need to wait to the previous context menu to be closed
         (timers/schedule
          #(st/emit!
            (if (some? @hover)
              (dw/show-shape-context-menu {:position position
                                           :shape @hover
                                           :hover-ids @hover-ids})
              (dw/show-context-menu {:position position})))))))))

(defn on-menu-selected
  [hover hover-ids selected]
  (mf/use-callback
   (mf/deps @hover @hover-ids selected)
   (fn [event]
     (dom/prevent-default event)
     (dom/stop-propagation event)
     (let [position (dom/get-client-position event)]
       (st/emit! (dw/show-shape-context-menu {:position position :hover-ids @hover-ids}))))))

(defn on-mouse-up
  [disable-paste]
  (mf/use-callback
   (fn [event]
     (dom/stop-propagation event)

     (let [event (.-nativeEvent event)
           ctrl? (kbd/ctrl? event)
           shift? (kbd/shift? event)
           alt? (kbd/alt? event)
           meta? (kbd/meta? event)

           left-click? (= 1 (.-which event))
           middle-click? (= 2 (.-which event))]

       (when left-click?
         (st/emit! (ms/->MouseEvent :up ctrl? shift? alt? meta?)))

       (when middle-click?
         (dom/prevent-default event)

         ;; We store this so in Firefox the middle button won't do a paste of the content
         (reset! disable-paste true)
         (timers/schedule #(reset! disable-paste false)))

       (st/emit! (dw/finish-panning)
                 (dw/finish-zooming))))))

(defn on-pointer-enter [in-viewport?]
  (mf/use-callback
   (fn []
     (reset! in-viewport? true))))

(defn on-pointer-leave [in-viewport?]
  (mf/use-callback
   (fn []
     (reset! in-viewport? false))))

(defn on-pointer-down []
  (mf/use-callback
   (fn [event]
    ;; We need to handle editor related stuff here because
    ;; handling on editor dom node does not works properly.
     (let [target  (dom/get-target event)
           editor (.closest ^js target ".public-DraftEditor-content")]
      ;; Capture mouse pointer to detect the movements even if cursor
      ;; leaves the viewport or the browser itself
      ;; https://developer.mozilla.org/en-US/docs/Web/API/Element/setPointerCapture
       (if editor
         (.setPointerCapture editor (.-pointerId event))
         (.setPointerCapture target (.-pointerId event)))))))

(defn on-pointer-up []
  (mf/use-callback
   (fn [event]
     (let [target (dom/get-target event)]
      ; Release pointer on mouse up
       (.releasePointerCapture target (.-pointerId event))))))

(defn on-key-down []
  (mf/use-callback
   (fn [event]
     (let [bevent   (.getBrowserEvent ^js event)
           key      (.-key ^js event)
           ctrl?    (kbd/ctrl? event)
           shift?   (kbd/shift? event)
           alt?     (kbd/alt? event)
           meta?    (kbd/meta? event)
           target   (dom/get-target event)
           editing? (or (some? (.closest ^js target ".public-DraftEditor-content"))
                        (= "rich-text" (obj/get target "className"))
                        (= "INPUT" (obj/get target "tagName"))
                        (= "TEXTAREA" (obj/get target "tagName")))]

       (when-not (.-repeat bevent)
         (st/emit! (ms/->KeyboardEvent :down key shift? ctrl? alt? meta? editing?)))))))

(defn on-key-up []
  (mf/use-callback
   (fn [event]
     (let [key    (.-key event)
           ctrl?  (kbd/ctrl? event)
           shift? (kbd/shift? event)
           alt?   (kbd/alt? event)
           meta?  (kbd/meta? event)
           target   (dom/get-target event)
           editing? (or (some? (.closest ^js target ".public-DraftEditor-content"))
                        (= "rich-text" (obj/get target "className"))
                        (= "INPUT" (obj/get target "tagName"))
                        (= "TEXTAREA" (obj/get target "tagName")))]
       (st/emit! (ms/->KeyboardEvent :up key shift? ctrl? alt? meta? editing?))))))

(defn on-mouse-move [viewport-ref zoom]
  (let [last-position (mf/use-var nil)]
    (mf/use-callback
     (mf/deps zoom)
     (fn [event]
       (let [event    (.getBrowserEvent ^js event)
             raw-pt   (dom/get-client-position event)
             viewport (mf/ref-val viewport-ref)
             pt       (utils/translate-point-to-viewport viewport zoom raw-pt)

             ;; We calculate the delta because Safari's MouseEvent.movementX/Y drop
             ;; events
             delta (if @last-position
                     (gpt/subtract raw-pt @last-position)
                     (gpt/point 0 0))]

         (reset! last-position raw-pt)
         (st/emit! (ms/->PointerEvent :delta delta
                                      (kbd/ctrl? event)
                                      (kbd/shift? event)
                                      (kbd/alt? event)
                                      (kbd/meta? event)))
         (st/emit! (ms/->PointerEvent :viewport pt
                                      (kbd/ctrl? event)
                                      (kbd/shift? event)
                                      (kbd/alt? event)
                                      (kbd/meta? event))))))))

(defn on-pointer-move [viewport-ref zoom move-stream]
  (mf/use-callback
   (mf/deps zoom move-stream)
   (fn [event]
     (let [raw-pt (dom/get-client-position event)
           viewport (mf/ref-val viewport-ref)
           pt     (utils/translate-point-to-viewport viewport zoom raw-pt)]
       (rx/push! move-stream pt)))))

(defn on-mouse-wheel [viewport-ref overlays-ref zoom]
  (mf/use-callback
   (mf/deps zoom)
   (fn [event]
     (let [viewport (mf/ref-val viewport-ref)
           overlays (mf/ref-val overlays-ref)
           event  (.getBrowserEvent ^js event)
           target (dom/get-target event)
           mod? (kbd/mod? event)]

       (when (or (dom/is-child? viewport target)
                 (dom/is-child? overlays target))
         (dom/prevent-default event)
         (dom/stop-propagation event)
         (let [pt     (->> (dom/get-client-position event)
                           (utils/translate-point-to-viewport viewport zoom))
               ctrl?  (kbd/ctrl? event)

               delta-mode (.-deltaMode ^js event)

               unit (cond
                      (= delta-mode WheelEvent.DeltaMode.PIXEL) 1
                      (= delta-mode WheelEvent.DeltaMode.LINE) 16
                      (= delta-mode WheelEvent.DeltaMode.PAGE) 100)

               delta-y (-> (.-deltaY ^js event)
                           (* unit)
                           (/ zoom))

               delta-x (-> (.-deltaX ^js event)
                           (* unit)
                           (/ zoom))]
           (if (or ctrl? mod?)
             (let [delta (* -1 (+ delta-y delta-x))
                   scale (-> (+ 1 (/ delta 100)) (mth/clamp 0.77 1.3))]
               (st/emit! (dw/set-zoom pt scale)))
             (if (and (not (cfg/check-platform? :macos))
                      ;; macos sends delta-x automatically, don't need to do it
                      (kbd/shift? event))
               (st/emit! (dw/update-viewport-position {:x #(+ % delta-y)}))
               (st/emit! (dw/update-viewport-position {:x #(+ % delta-x)
                                                       :y #(+ % delta-y)}))))))))))

(defn on-drag-enter []
  (mf/use-callback
   (fn [e]
     (when (or (dnd/has-type? e "penpot/shape")
               (dnd/has-type? e "penpot/component")
               (dnd/has-type? e "Files")
               (dnd/has-type? e "text/uri-list")
               (dnd/has-type? e "text/asset-id"))
       (dom/prevent-default e)))))

(defn on-drag-over []
  (mf/use-callback
   (fn [e]
     (when (or (dnd/has-type? e "penpot/shape")
               (dnd/has-type? e "penpot/component")
               (dnd/has-type? e "Files")
               (dnd/has-type? e "text/uri-list")
               (dnd/has-type? e "text/asset-id"))
       (dom/prevent-default e)))))

(defn on-drop
  [file viewport-ref zoom]
  (mf/use-fn
   (mf/deps zoom)
   (fn [event]
     (dom/prevent-default event)
     (let [point (gpt/point (.-clientX event) (.-clientY event))
           viewport (mf/ref-val viewport-ref)
           viewport-coord (utils/translate-point-to-viewport viewport zoom point)
           asset-id     (-> (dnd/get-data event "text/asset-id") uuid/uuid)
           asset-name   (dnd/get-data event "text/asset-name")
           asset-type   (dnd/get-data event "text/asset-type")]
       (cond
         (dnd/has-type? event "penpot/shape")
         (let [shape (dnd/get-data event "penpot/shape")
               final-x (- (:x viewport-coord) (/ (:width shape) 2))
               final-y (- (:y viewport-coord) (/ (:height shape) 2))]
           (st/emit! (dw/add-shape (-> shape
                                       (assoc :id (uuid/next))
                                       (assoc :x final-x)
                                       (assoc :y final-y)))))

         (dnd/has-type? event "penpot/component")
         (let [{:keys [component file-id]} (dnd/get-data event "penpot/component")
               shape (get-in component [:objects (:id component)])
               final-x (- (:x viewport-coord) (/ (:width shape) 2))
               final-y (- (:y viewport-coord) (/ (:height shape) 2))]
           (st/emit! (dwl/instantiate-component file-id
                                                (:id component)
                                                (gpt/point final-x final-y))))

         ;; Will trigger when the user drags an image from a browser to the viewport
         (dnd/has-type? event "text/uri-list")
         (let [data  (dnd/get-data event "text/uri-list")
               lines (str/lines data)
               uris  (filter #(and (not (str/blank? %))
                                   (not (str/starts-with? % "#")))
                             lines)
               params {:file-id (:id file)
                       :position viewport-coord
                       :uris uris}]
           (st/emit! (dwm/upload-media-workspace params)))

         ;; Will trigger when the user drags an SVG asset from the assets panel
         (and (dnd/has-type? event "text/asset-id") (= asset-type "image/svg+xml"))
         (let [path (cfg/resolve-file-media {:id asset-id})
               params {:file-id (:id file)
                       :position viewport-coord
                       :uris [path]
                       :name asset-name
                       :mtype asset-type}]
           (st/emit! (dwm/upload-media-workspace params)))

         ;; Will trigger when the user drags an image from the assets SVG
         (dnd/has-type? event "text/asset-id")
         (let [params {:file-id (:id file)
                       :object-id asset-id
                       :name asset-name}]
           (st/emit! (dwm/clone-media-object
                      (with-meta params
                        {:on-success #(st/emit! (dwm/image-uploaded % viewport-coord))}))))

         ;; Will trigger when the user drags a file from their file explorer into the viewport
         ;; Or the user pastes an image
         ;; Or the user uploads an image using the image tool
         :else
         (let [files  (dnd/get-files event)
               params {:file-id (:id file)
                       :position viewport-coord
                       :blobs (seq files)}]
           (st/emit! (dwm/upload-media-workspace params))))))))

(defn on-paste [disable-paste in-viewport?]
  (mf/use-callback
   (fn [event]
    ;; We disable the paste just after mouse-up of a middle button so when panning won't
    ;; paste the content into the workspace
     (let [tag-name (-> event dom/get-target dom/get-tag-name)]
       (when (and (not (#{"INPUT" "TEXTAREA"} tag-name)) (not @disable-paste))
         (st/emit! (dw/paste-from-event event @in-viewport?)))))))

