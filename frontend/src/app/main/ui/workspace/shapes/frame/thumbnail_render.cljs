;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.frame.thumbnail-render
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.config :as cf]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.shapes.frame :as frame]
   [app.util.dom :as dom]
   [app.util.thumbnails :as th]
   [app.util.timers :as ts]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [debug :refer [debug?]]
   [rumext.v2 :as mf]))

(defn- remove-image-loading
  "Remove the changes related to change a url for its embed value. This is necessary
  so we don't have to recalculate the thumbnail when the image loads."
  [value]
  (if (.isArray js/Array value)
    (->> value
         (remove (fn [change]
                   (or (= "data-loading" (.-attributeName change))
                       (and (= "attributes" (.-type change))
                            (= "href" (.-attributeName change))
                            (str/starts-with? (.-oldValue change) "http"))))))
    [value]))

(defn- create-svg-blob-uri-from
  [rect node style-node]
  (let [{:keys [x y width height]} rect
        viewbox (dm/str x " " y " " width " " height)

        ;; Calculate the fixed width and height
        ;; We don't want to generate thumbnails
        ;; bigger than 2000px
        [fixed-width fixed-height] (th/get-proportional-size width height)

        ;; This is way faster than creating a node
        ;; through the DOM API
        svg-data
        (dm/fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" viewBox=\"%\" width=\"%\" height=\"%\" fill=\"none\">% %</svg>"
                viewbox
                fixed-width
                fixed-height
                (if (some? style-node) (dom/node->xml style-node) "")
                (dom/node->xml node))

        ;; create SVG blob
        blob (wapi/create-blob svg-data "image/svg+xml;charset=utf-8")
        url  (dm/str (wapi/create-uri blob) "#svg")]
    ;; returns the url and the node
    url))

(defn use-render-thumbnail
  "Hook that will create the thumbnail data"
  [page-id {:keys [id] :as shape} root-ref node-ref rendered-ref disable? force-render]

  (let [frame-image-ref  (mf/use-ref nil)

        disable-ref      (mf/use-ref disable?)
        regenerate-ref   (mf/use-ref false)

        all-children-ref (mf/with-memo [id]
                           (refs/all-children-objects id))
        all-children     (mf/deref all-children-ref)

        bounds
        (if (:show-content shape)
          (gsh/shapes->rect (cons shape all-children))
          (-> shape :points grc/points->rect))

        x                 (dm/get-prop bounds :x)
        y                 (dm/get-prop bounds :y)
        width             (dm/get-prop bounds :width)
        height            (dm/get-prop bounds :height)

        svg-uri*          (mf/use-state nil)
        svg-uri           (deref svg-uri*)

        bitmap-uri*       (mf/use-state nil)
        bitmap-uri        (deref bitmap-uri*)

        observer-ref      (mf/use-ref nil)

        bounds-ref        (hooks/use-update-ref bounds)
        updates-s         (mf/use-memo rx/subject)

        thumbnail-uri* (mf/with-memo [page-id id]
                         (refs/thumbnail-frame-data page-id id))
        thumbnail-uri  (mf/deref thumbnail-uri*)

        ;; State to indicate to the parent that should render the frame
        render-frame*     (mf/use-state (not thumbnail-uri))
        render-frame?     (deref render-frame*)

        debug?            (debug? :thumbnails)

        on-bitmap-load
        (mf/use-fn
         (mf/deps svg-uri)
         (fn []
           ;; We revoke the SVG Blob URI to free memory only when we
           ;; are sure that it is not used anymore.
           (some-> svg-uri wapi/revoke-uri)
           (reset! svg-uri* nil)))

        on-svg-load
        (mf/use-fn
         (mf/deps thumbnail-uri)
         (fn []
           ;; If we don't have the thumbnail data saved (normally the first load) we update the data
           ;; when available
           (when-not (some? thumbnail-uri)
             (st/emit! (dwt/update-thumbnail page-id id)))

           (reset! render-frame* false)))

        generate-thumbnail
        (mf/use-fn
         (mf/deps id)
         (fn generate-thumbnail []
           (try
             ;; When starting generating the canvas we mark it as not ready so its not send to back until
             ;; we have time to update it

             (when-let [node (mf/ref-val node-ref)]
               (if (dom/has-children? node)
                 ;; The frame-content need to have children in order to generate the thumbnail
                 (let [style-node (dom/query (dm/str "#frame-container-" id " style"))
                       bounds     (mf/ref-val bounds-ref)
                       url        (create-svg-blob-uri-from bounds node style-node)]

                   (reset! svg-uri* url))

                 ;; Node not yet ready, we schedule a new generation
                 (ts/raf generate-thumbnail)))
             (catch :default e
               (.error js/console e)))))

        on-change-frame
        (mf/use-fn
         (mf/deps id generate-thumbnail)
         (fn []
           (when (and (some? (mf/ref-val node-ref))
                      (some? (mf/ref-val rendered-ref))
                      (some? (mf/ref-val regenerate-ref)))
             (let [node            (mf/ref-val node-ref)
                   loading-images? (some? (dom/query node "[data-loading='true']"))
                   loading-fonts?  (some? (dom/query (dm/str "#frame-container-" id " > style[data-loading='true']")))]
               (when (and (not loading-images?)
                          (not loading-fonts?))
                 (reset! svg-uri* nil)
                 (reset! bitmap-uri* nil)
                 (generate-thumbnail)
                 (mf/set-ref-val! regenerate-ref false))))))

        ;; When the frame is updated, it is marked as not ready
        ;; so that it is not sent to the background until
        ;; it is regenerated.
        on-update-frame
        (mf/use-fn
         (fn []
           (when-not ^boolean (mf/ref-val disable-ref)
             (reset! svg-uri* nil)
             (reset! bitmap-uri* nil)
             (reset! render-frame* true)
             (mf/set-ref-val! regenerate-ref true))))

        on-load-frame-dom
        (mf/use-fn
         (fn [node]
           (when (and (nil? (mf/ref-val observer-ref)) (some? node))
             (when-not (some? @thumbnail-uri*)
               (rx/push! updates-s :update))

             (let [observer (js/MutationObserver. (partial rx/push! updates-s))]
               (.observe observer node #js {:childList true
                                            :attributes true
                                            :attributeOldValue true
                                            :characterData true
                                            :subtree true})
               (mf/set-ref-val! observer-ref observer)))))]

    (mf/with-effect [thumbnail-uri]
      (when (some? thumbnail-uri)
        (reset! bitmap-uri* thumbnail-uri)))

    (mf/with-effect [force-render]
      (when ^boolean force-render
        (rx/push! updates-s :update)))

    (mf/with-effect []
      (let [subid (->> updates-s
                       (rx/map remove-image-loading)
                       (rx/filter d/not-empty?)
                       (rx/catch (fn [err] (.error js/console err)))
                       (rx/subs on-update-frame))]
        (partial rx/dispose! subid)))

    ;; on-change-frame will get every change in the frame
    (mf/with-effect []
      (let [subid (->> updates-s
                       (rx/debounce 400)
                       (rx/observe-on :af)
                       (rx/catch (fn [err] (.error js/console err)))
                       (rx/subs on-change-frame))]
        (partial rx/dispose! subid)))

    (mf/with-effect [disable?]
      (when (and ^boolean disable? (not (mf/ref-val disable-ref)))
        (rx/push! updates-s :update))

      (mf/set-ref-val! disable-ref disable?)
      nil)

    (mf/with-effect []
      (fn []
        (when (and (some? (mf/ref-val node-ref))
                   (true? (mf/ref-val rendered-ref)))
          (when-let [root (mf/ref-val root-ref)]
            ;; NOTE: the unmount should be always scheduled to be
            ;; executed asynchronously of the current flow (react
            ;; rules).
            (ts/schedule #(mf/unmount! ^js root)))

          (mf/set-ref-val! node-ref nil)
          (mf/set-ref-val! rendered-ref false)

          (when-let [observer (mf/ref-val observer-ref)]
            (.disconnect ^js observer)
            (mf/set-ref-val! observer-ref nil)))))

    [on-load-frame-dom render-frame?
     (mf/html
      [:& frame/frame-container {:bounds bounds :shape shape}

       ;; Safari don't support filters so instead we add a rectangle around the thumbnail
       (when (and (cf/check-browser? :safari)
                  ^boolean debug?)
         [:rect {:x (+ x 2)
                 :y (+ y 2)
                 :width (- width 4)
                 :height (- height 4)
                 :stroke "blue"
                 :stroke-width 2}])

       ;; This is similar to how double-buffering works.
       ;; In svg-uri* we keep the SVG image that is used to
       ;; render the bitmap until the bitmap is ready
       ;; to be rendered on screen. Then we remove the
       ;; svg and keep the bitmap one.
       ;; This is the "buffer" that keeps the bitmap image.
       (when (some? bitmap-uri)
         [:image.thumbnail-bitmap
          {:x x
           :y y
           :width width
           :height height
           :href bitmap-uri
           :style {:filter (when ^boolean debug? "sepia(1)")}
           :on-load on-bitmap-load}])

       ;; This is the "buffer" that keeps the SVG image.
       (when (some? svg-uri)
         [:image.thumbnail-canvas
          {:x x
           :y y
           :key (dm/str "thumbnail-canvas-" id)
           :data-object-id (dm/str page-id id)
           :width width
           :height height
           :ref frame-image-ref
           :href svg-uri
           :style {:filter (when ^boolean debug? "sepia(0.5)")}
           :on-load on-svg-load}])])]))
