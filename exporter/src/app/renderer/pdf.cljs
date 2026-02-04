;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.renderer.pdf
  "A pdf renderer."
  (:require
   [app.browser :as bw]
   [app.common.data.macros :as dm]
   [app.common.logging :as l]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.util.mime :as mime]
   [app.util.shell :as sh]
   [promesa.core :as p]))

(defn render
  [{:keys [file-id page-id share-id token scale type objects] :as params} on-object]
  (letfn [(prepare-options [uri]
            #js {:screen #js {:width bw/default-viewport-width
                              :height bw/default-viewport-height}
                 :viewport #js {:width bw/default-viewport-width
                                :height bw/default-viewport-height}
                 :locale "en-US"
                 :storageState #js {:cookies (bw/create-cookies uri {:token token})}
                 :deviceScaleFactor scale
                 :userAgent bw/default-user-agent})

          (prepare-uri [base-uri object-id]
            (let [params {:file-id file-id
                          :page-id page-id
                          :share-id share-id
                          :object-id object-id
                          :route "objects"}]
              (-> base-uri
                  (assoc :path "/render.html")
                  (assoc :query (u/map->query-string params)))))

          (wait-for-render [page dom]
            (p/loop [attempt 0]
              (p/let [size (bw/eval! dom
                                     (fn [elem]
                                       (let [rect (.getBoundingClientRect ^js elem)]
                                         #js {:width (.-width rect)
                                              :height (.-height rect)})))]
                (let [width (some-> size (unchecked-get "width"))
                      height (some-> size (unchecked-get "height"))
                      ready? (and (number? width) (number? height) (> width 1) (> height 1))
                      max-attempts 15]
                  (cond
                    ready?
                    true

                    (< attempt max-attempts)
                    (p/let [_ (bw/sleep page 200)]
                      (p/recur (inc attempt)))

                    :else
                    false)))))

          (sync-page-size! [dom]
            (bw/eval! dom
                      (fn [elem]
                        (let [attr-w (.getAttribute ^js elem "width")
                              attr-h (.getAttribute ^js elem "height")
                              rect (.getBoundingClientRect ^js elem)
                              attr-width (when (and attr-w (not= attr-w ""))
                                           (js/parseFloat attr-w))
                              attr-height (when (and attr-h (not= attr-h ""))
                                            (js/parseFloat attr-h))
                              rect-width (.-width rect)
                              rect-height (.-height rect)
                              width (js/Math.max (or attr-width 0) rect-width)
                              height (js/Math.max (or attr-height 0) rect-height)
                              width-px (str width "px")
                              height-px (str height "px")
                              root (.-documentElement js/document)
                              body (.-body js/document)
                              app (.querySelector js/document "#app")
                              head (.-head js/document)
                              style-id "penpot-pdf-page-size"
                              style-node (or (.getElementById js/document style-id)
                                             (let [node (.createElement js/document "style")]
                                               (set! (.-id node) style-id)
                                               (.appendChild head node)
                                               node))
                              css (str "@page { size: " width-px " " height-px "; margin: 0; }\n"
                                       "html, body, #app { margin: 0; padding: 0; }\n")]
                          (set! (.-textContent style-node) css)
                          (set! (.-width (.-style root)) width-px)
                          (set! (.-height (.-style root)) height-px)
                          (set! (.-overflow (.-style root)) "visible")
                          (set! (.-width (.-style body)) width-px)
                          (set! (.-height (.-style body)) height-px)
                          (set! (.-overflow (.-style body)) "visible")
                          (when app
                            (set! (.-width (.-style app)) width-px)
                            (set! (.-height (.-style app)) height-px)
                            (set! (.-overflow (.-style app)) "visible"))))))

          (render-object [page base-uri {:keys [id] :as object}]
            (p/let [uri  (prepare-uri base-uri id)
                    path (sh/tempfile :prefix "penpot.tmp.pdf." :suffix (mime/get-extension type))]
              (l/info :uri uri)
              (bw/nav! page uri)
              (p/let [dom (bw/select page (dm/str "#screenshot-" id))]
                (bw/wait-for dom)
                (wait-for-render page dom)
                (sync-page-size! dom)
                (bw/screenshot dom {:full-page? true})
                (bw/sleep page 2000) ; the good old fix with sleep
                (bw/pdf page {:path path})
                path)))

          (render [base-uri page]
            (p/loop [objects (seq objects)]
              (when-let [object (first objects)]
                (p/let [path (render-object page base-uri object)]
                  (on-object (assoc object :path path))
                  (p/recur (rest objects))))))]

    (let [base-uri (cf/get :public-uri)]
      (bw/exec! (prepare-options base-uri)
                (partial render base-uri)))))
