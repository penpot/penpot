;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.actions
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.drawing :as dd]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.path :as dwdp]
   [app.main.data.workspace.specialized-panel :as-alias dwsp]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.sidebar.assets.components :as wsac]
   [app.main.ui.workspace.viewport.viewport-ref :as uwvv]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.dom.normalize-wheel :as nw]
   [app.util.keyboard :as kbd]
   [app.util.mouse :as mse]
   [app.util.object :as obj]
   [app.util.rxops :refer [throttle-fn]]
   [app.util.text.ui :as txu]
   [app.util.timers :as ts]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def scale-per-pixel -0.0057)

(defn on-pointer-down
  [{:keys [id blocked hidden type]} selected edition drawing-tool text-editing?
   node-editing? grid-editing? drawing-path? create-comment? space? panning z? read-only?]
  (mf/use-callback
   (mf/deps id blocked hidden type selected edition drawing-tool text-editing?
            node-editing? grid-editing? drawing-path? create-comment? @z? @space?
            panning read-only?)
   (fn [bevent]
     ;; We need to handle editor related stuff here because
     ;; handling on editor dom node does not works properly.
     (let [target  (dom/get-target bevent)
           editor  (txu/closest-text-editor-content target)]
       ;; Capture mouse pointer to detect the movements even if cursor
       ;; leaves the viewport or the browser itself
       ;; https://developer.mozilla.org/en-US/docs/Web/API/Element/setPointerCapture
       (if editor
         (.setPointerCapture editor (.-pointerId bevent))
         (.setPointerCapture target (.-pointerId bevent))))

     (when (or (dom/class? (dom/get-target bevent) "viewport-controls")
               (dom/class? (dom/get-target bevent) "viewport-selrect")
               (dom/child? (dom/get-target bevent) (dom/query ".grid-layout-editor")))

       (dom/stop-propagation bevent)

       (when-not @z?
         (let [event  (dom/event->native-event bevent)
               ctrl?  (kbd/ctrl? event)
               meta?  (kbd/meta? event)
               shift? (kbd/shift? event)
               alt?   (kbd/alt? event)
               mod?   (kbd/mod? event)

               left-click?   (and (not panning) (dom/left-mouse? bevent))
               middle-click? (and (not panning) (dom/middle-mouse? bevent))]

           (cond
             (or middle-click? (and left-click? @space?))
             (do
               (dom/prevent-default bevent)
               (if mod?
                 (let [raw-pt   (dom/get-client-position event)
                       pt       (uwvv/point->viewport raw-pt)]
                   (st/emit! (dw/start-zooming pt)))
                 (st/emit! (dw/start-panning))))

             left-click?
             (do
               (st/emit! (mse/->MouseEvent :down ctrl? shift? alt? meta?)
                         ::dwsp/interrupt)

               (when (and (not= edition id) (or text-editing? grid-editing?))
                 (st/emit! (dw/clear-edition-mode)))

               (when (and (not text-editing?)
                          (not blocked)
                          (not hidden)
                          (not create-comment?)
                          (not drawing-path?))
                 (cond
                   node-editing?
                   ;; Handle path node area selection
                   (when-not read-only?
                     (st/emit! (dwdp/handle-area-selection shift?)))

                   drawing-tool
                   (when-not read-only?
                     (st/emit! (dd/start-drawing drawing-tool)))

                   (or (not id) mod?)
                   (st/emit! (dw/handle-area-selection shift?))

                   (not drawing-tool)
                   (when-not read-only?
                     (st/emit! (dw/start-move-selected id shift?)))))))))))))

(defn on-move-selected
  [hover hover-ids selected space? z? read-only?]
  (mf/use-callback
   (mf/deps @hover @hover-ids selected @space? @z? read-only?)
   (fn [bevent]
     (let [event  (dom/event->native-event bevent)
           shift? (kbd/shift? event)
           mod?   (kbd/mod? event)]

       (when (and (dom/left-mouse? bevent)
                  (not mod?)
                  (not shift?)
                  (not @space?))

         (dom/prevent-default bevent)
         (dom/stop-propagation bevent)
         (when-not (or read-only? @z?)
           (st/emit! (dw/start-move-selected))))))))

(defn on-frame-select
  [selected read-only?]
  (mf/use-callback
   (mf/deps selected read-only?)
   (fn [event id]
     (let [shift? (kbd/shift? event)
           selected? (contains? selected id)
           selected-drawtool (deref refs/selected-drawing-tool)]
       (st/emit! (when (or shift? (not selected?))
                   (dw/select-shape id shift?))
                 (when (and (nil? selected-drawtool) (not shift?) (not read-only?))
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
  [hover selected edition drawing-path? drawing-tool space? selrect z?]
  (mf/use-callback
   (mf/deps @hover selected edition drawing-path? drawing-tool @space? selrect @z?)
   (fn [event]
     (when (and (nil? selrect)
                (or (dom/class? (dom/get-target event) "viewport-controls")
                    (dom/child? (dom/get-target event) (dom/query ".grid-layout-editor"))
                    (dom/class? (dom/get-target event) "viewport-selrect")))
       (let [ctrl? (kbd/ctrl? event)
             shift? (kbd/shift? event)
             alt? (kbd/alt? event)
             meta? (kbd/meta? event)
             hovering? (some? @hover)
             raw-pt (dom/get-client-position event)
             pt     (uwvv/point->viewport raw-pt)]
         (st/emit! (mse/->MouseEvent :click ctrl? shift? alt? meta?))

         (when (and hovering?
                    (not @space?)
                    (not edition)
                    (not drawing-path?)
                    (not drawing-tool))
           (st/emit! (dw/select-shape (:id @hover) shift?)))

         (when (and @z?
                    (not @space?)
                    (not edition)
                    (not drawing-path?)
                    (not drawing-tool))
           (if alt?
             (st/emit! (dw/decrease-zoom pt))
             (st/emit! (dw/increase-zoom pt)))))))))

(defn on-double-click
  [hover hover-ids hover-top-frame-id drawing-path? objects edition drawing-tool z? read-only?]

  (mf/use-callback
   (mf/deps @hover @hover-ids @hover-top-frame-id drawing-path? edition drawing-tool @z? read-only?)
   (fn [event]
     (dom/stop-propagation event)
     (when-not @z?
       (let [ctrl? (kbd/ctrl? event)
             shift? (kbd/shift? event)
             alt? (kbd/alt? event)
             meta? (kbd/meta? event)

             {:keys [id type] :as shape} (or @hover (get objects (first @hover-ids)))

             editable? (contains? #{:text :rect :path :image :circle} type)

             hover-shape (->> @hover-ids (filter (partial cfh/is-child? objects id)) first)
             selected-shape (get objects hover-shape)

             grid-layout-id (->> @hover-ids reverse (d/seek (partial ctl/grid-layout? objects)))]

         (st/emit! (mse/->MouseEvent :double-click ctrl? shift? alt? meta?))

         ;; Emit asynchronously so the double click to exit shapes won't break
         (ts/schedule
          (fn []
            (when (and (not drawing-path?) shape)
              (cond
                (and editable? (not= id edition) (not read-only?))
                (st/emit! (dw/select-shape id)
                          (dw/start-editing-selected))

                (some? selected-shape)
                (do (reset! hover selected-shape)
                    (st/emit! (dw/select-shape (:id selected-shape))))

                (and (not selected-shape) (some? grid-layout-id) (not read-only?))
                (st/emit! (dw/start-edition-mode grid-layout-id)))))))))))

(defn on-context-menu
  [hover hover-ids read-only?]
  (mf/use-fn
   (mf/deps @hover @hover-ids read-only?)
   (fn [event]
     (dom/prevent-default event)
     ;;(when-not read-only?
     (when (or (dom/class? (dom/get-target event) "viewport-controls")
               (dom/child? (dom/get-target event) (dom/query ".grid-layout-editor"))
               (dom/class? (dom/get-target event) "viewport-selrect"))
       (let [position (dom/get-client-position event)]
           ;; Delayed callback because we need to wait to the previous context menu to be closed
         (ts/schedule
          #(st/emit!
            (if (and (not read-only?) (some? @hover))
              (dw/show-shape-context-menu {:position position
                                           :shape @hover
                                           :hover-ids @hover-ids})
              (dw/show-context-menu {:position position})))))))))

(defn on-menu-selected
  [hover hover-ids selected read-only?]
  (mf/use-callback
   (mf/deps @hover @hover-ids selected read-only?)
   (fn [event]
     (dom/prevent-default event)
     (dom/stop-propagation event)
     (when-not read-only?
       (let [position (dom/get-client-position event)]
         (st/emit! (dw/show-shape-context-menu {:position position :hover-ids @hover-ids})))))))

(defn on-pointer-up
  [disable-paste]
  (mf/use-callback
   (fn [event]
     (dom/stop-propagation event)

     (let [target (dom/get-target event)]
       ;; Release pointer on mouse up
       (.releasePointerCapture target (.-pointerId event)))

     (let [event (dom/event->native-event event)
           ctrl? (kbd/ctrl? event)
           shift? (kbd/shift? event)
           alt? (kbd/alt? event)
           meta? (kbd/meta? event)

           left-click? (= 1 (.-which event))
           middle-click? (= 2 (.-which event))]

       (when left-click?
         (st/emit! (mse/->MouseEvent :up ctrl? shift? alt? meta?)))

       (when middle-click?
         (dom/prevent-default event)

         ;; We store this so in Firefox the middle button won't do a paste of the content
         (reset! disable-paste true)
         (ts/schedule #(reset! disable-paste false)))

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

(defn on-key-down []
  (mf/use-callback
   (fn [event]
     (let [bevent   (.getBrowserEvent ^js event)
           key      (.-key ^js event)
           ctrl?    (kbd/ctrl? event)
           shift?   (kbd/shift? event)
           alt?     (kbd/alt? event)
           meta?    (kbd/meta? event)
           mod?     (kbd/mod? event)
           target   (dom/get-target event)

           editing? (or (txu/some-text-editor-content? target)
                        (= "rich-text" (obj/get target "className"))
                        (= "INPUT" (obj/get target "tagName"))
                        (= "TEXTAREA" (obj/get target "tagName")))]

       (when-not (.-repeat bevent)
         (st/emit! (kbd/->KeyboardEvent :down key shift? ctrl? alt? meta? mod? editing? event)))))))

(defn on-key-up []
  (mf/use-callback
   (fn [event]
     (let [key      (.-key event)
           ctrl?    (kbd/ctrl? event)
           shift?   (kbd/shift? event)
           alt?     (kbd/alt? event)
           meta?    (kbd/meta? event)
           mod?     (kbd/mod? event)
           target   (dom/get-target event)

           editing? (or (txu/some-text-editor-content? target)
                        (= "rich-text" (obj/get target "className"))
                        (= "INPUT" (obj/get target "tagName"))
                        (= "TEXTAREA" (obj/get target "tagName")))]
       (st/emit! (kbd/->KeyboardEvent :up key shift? ctrl? alt? meta? mod? editing? event))))))

(defn on-pointer-move [move-stream]
  (let [last-position (mf/use-var nil)]
    (mf/use-fn
     (fn [event]
       (let [raw-pt   (dom/get-client-position event)
             pt       (uwvv/point->viewport raw-pt)

             ;; We calculate the delta because Safari's MouseEvent.movementX/Y drop
             ;; events
             delta (if @last-position
                     (gpt/subtract raw-pt @last-position)
                     (gpt/point 0 0))]

         (rx/push! move-stream pt)
         (reset! last-position raw-pt)
         (st/emit! (mse/->PointerEvent :delta delta
                                       (kbd/ctrl? event)
                                       (kbd/shift? event)
                                       (kbd/alt? event)
                                       (kbd/meta? event)))
         (st/emit! (mse/->PointerEvent :viewport pt
                                       (kbd/ctrl? event)
                                       (kbd/shift? event)
                                       (kbd/alt? event)
                                       (kbd/meta? event))))))))

(defn on-mouse-wheel [zoom]
  (mf/use-callback
   (mf/deps zoom)
   (fn [event]
     (let [event      (.getBrowserEvent ^js event)

           target     (dom/get-target event)
           mod?       (kbd/mod? event)
           ctrl?      (kbd/ctrl? event)

           picking-color?   (= "pixel-overlay" (.-id target))
           comments-layer?  (dom/is-child? (dom/get-element "comments") target)

           raw-pt     (dom/get-client-position event)
           pt         (uwvv/point->viewport raw-pt)

           norm-event ^js (nw/normalize-wheel event)

           delta-y    (.-pixelY norm-event)
           delta-x    (.-pixelX norm-event)
           delta-zoom (+ delta-y delta-x)

           scale      (+ 1 (mth/abs (* scale-per-pixel delta-zoom)))
           scale      (if (pos? delta-zoom) (/ 1 scale) scale)]

       (when (or (uwvv/inside-viewport? target) picking-color?)
         (dom/prevent-default event)
         (dom/stop-propagation event)
         (if (or ctrl? mod?)
           (st/emit! (dw/set-zoom pt scale))
           (if (and (not (cfg/check-platform? :macos)) (kbd/shift? event))
             ;; macos sends delta-x automatically, don't need to do it
             (st/emit! (dw/update-viewport-position {:x #(+ % (/ delta-y zoom))}))
             (st/emit! (dw/update-viewport-position {:x #(+ % (/ delta-x zoom))
                                                     :y #(+ % (/ delta-y zoom))})))))

       (when (and comments-layer? (or ctrl? mod?))
         (dom/prevent-default event)
         (dom/stop-propagation event)
         (st/emit! (dw/set-zoom pt scale)))))))

(defn on-drag-enter
  [comp-inst-ref]
  (mf/use-callback
   (fn [e]
     (let [component-inst? (mf/ref-val comp-inst-ref)]
       (when (and (dnd/has-type? e "penpot/component")
                  (dom/class? (dom/get-target e) "viewport-controls")
                  (not component-inst?))
         (let [point (gpt/point (.-clientX e) (.-clientY e))
               viewport-coord (uwvv/point->viewport point)
               {:keys [component file-id shape]} @wsac/drag-data*

               ;; shape (get-in component [:objects (:id component)])
               final-x (- (:x viewport-coord) (/ (:width shape) 2))
               final-y (- (:y viewport-coord) (/ (:height shape) 2))]

           (mf/set-ref-val! comp-inst-ref true)
           (st/emit! (dwl/instantiate-component
                      file-id
                      (:id component)
                      (gpt/point final-x final-y)
                      {:start-move? true :initial-point viewport-coord :origin "sidebar"})))))
     (when (or (dnd/has-type? e "penpot/shape")
               (dnd/has-type? e "penpot/component")
               (dnd/has-type? e "Files")
               (dnd/has-type? e "text/uri-list")
               (dnd/has-type? e "text/asset-id"))
       (dom/prevent-default e)))))

(defn on-drag-end
  [comp-inst-ref]
  (mf/use-callback
   (fn []
     (mf/set-ref-val! comp-inst-ref false))))

(defn on-drag-over [move-stream]
  (let [on-pointer-move (on-pointer-move move-stream)

        ;; Drag-over is not the same as pointer-move. Drag over is fired less frequently so we need
        ;; to create a throttle so the events that cannot be processed at a certain path are
        ;; discarded.
        on-pointer-move (throttle-fn 50 (fn [e] (ts/raf #(on-pointer-move e))))]
    (mf/use-callback
     (fn [e]
       (when (or (dnd/has-type? e "penpot/shape")
                 (dnd/has-type? e "penpot/component")
                 (dnd/has-type? e "Files")
                 (dnd/has-type? e "text/uri-list")
                 (dnd/has-type? e "text/asset-id"))
         (on-pointer-move e)
         (dom/prevent-default e))))))

(defn on-drop
  [file comp-inst-ref]
  (mf/use-fn
   (fn [event]
     (dom/prevent-default event)
     (let [point (gpt/point (.-clientX event) (.-clientY event))
           viewport-coord (uwvv/point->viewport point)
           asset-id     (-> (dnd/get-data event "text/asset-id") uuid/uuid)
           asset-name   (dnd/get-data event "text/asset-name")
           asset-type   (dnd/get-data event "text/asset-type")]
       (cond
         (dnd/has-type? event "penpot/shape")
         (let [shape   (dnd/get-data event "penpot/shape")
               final-x (- (:x viewport-coord) (/ (:width shape) 2))
               final-y (- (:y viewport-coord) (/ (:height shape) 2))]
           (st/emit! (dw/add-shape (-> shape
                                       (assoc :id (uuid/next))
                                       (assoc :x final-x)
                                       (assoc :y final-y)))))

         (dnd/has-type? event "penpot/component")
         (let [event (dom/event->native-event event)
               ctrl? (kbd/ctrl? event)
               shift? (kbd/shift? event)
               alt? (kbd/alt? event)
               meta? (kbd/meta? event)]
           (st/emit! (mse/->MouseEvent :up ctrl? shift? alt? meta?))
           (mf/set-ref-val! comp-inst-ref false))

         ;; Will trigger when the user drags an image from a browser
         ;; to the viewport (firefox and chrome do it a bit different
         ;; depending on the origin)
         (dnd/has-type? event "Files")
         (let [files  (dnd/get-files event)
               params {:file-id (:id file)
                       :position viewport-coord
                       :blobs (seq files)}]
           (st/emit! (dwm/upload-media-workspace params)))

         ;; Will trigger when the user drags an image (usually rendered as datauri) from a
         ;; browser to the viewport (mainly on firefox, all depending on the origin of the
         ;; drag event).
         (dnd/has-type? event "text/uri-list")
         (let [data   (dnd/get-data event "text/uri-list")
               lines  (str/lines data)
               uris   (filterv #(str/starts-with? % "http") lines)
               data   (filterv #(str/starts-with? % "data:image/") lines)
               params {:file-id (:id file)
                       :position viewport-coord}
               params (if (seq uris)
                        (assoc params :uris uris)
                        (assoc params :blobs (map wapi/data-uri->blob data)))]
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

(defn on-paste
  [disable-paste in-viewport? read-only?]
  (mf/use-fn
   (mf/deps read-only?)
   (fn [event]
     ;; We disable the paste just after mouse-up of a middle button so
     ;; when panning won't paste the content into the workspace
     (let [tag-name (-> event dom/get-target dom/get-tag-name)]
       (when (and (not (#{"INPUT" "TEXTAREA"} tag-name))
                  (not @disable-paste)
                  (not read-only?))
         (st/emit! (dw/paste-from-event event @in-viewport?)))))))
