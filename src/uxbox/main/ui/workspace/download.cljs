;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.download
  (:require [lentes.core :as l]
            [uxbox.main.state :as st]
            [uxbox.util.rstore :as rs]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.exports :as exports]
            [uxbox.util.blob :as blob]
            [uxbox.util.mixins :as mx]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (read-string)]))

;; --- Refs

(defn- resolve-pages
  [state]
  (let [project (get-in state [:workspace :project])]
    (->> (vals (:pages-by-id state))
         (filter #(= project (:project %)))
         (sort-by :created-at))))

(def pages-ref
  (-> (l/lens resolve-pages)
      (l/derive st/state)))

(def current-page-ref
  (-> (l/in [:workspace :page])
      (l/derive st/state)))

;; --- Download Lightbox (Component)

(defn- download-page-svg
  [id]
  (let [page (get-in @st/state [:pages-by-id id])
        page-name (:name page)
        content (exports/render-page id)
        blob (blob/create content "image/svg+xml")
        uri  (blob/create-uri blob)
        link (.createElement js/document "a")
        event (js/MouseEvent. "click")]

    ;; FIXME: use str/slug for proper file naming
    (.setAttribute link "href" uri)
    (.setAttribute link "download" (str page-name ".svg"))

    (.appendChild (.-body js/document) link)
    (.dispatchEvent link event)
    (blob/revoke-uri uri)
    (.removeChild (.-body js/document) link)))

(mx/defcs download-dialog
  {:mixins [mx/static mx/reactive]}
  [own]
  (letfn [(on-close [event]
            (dom/prevent-default event)
            (udl/close!))
          (download-page [event]
            (dom/prevent-default event)
            (let [id (-> (mx/ref-node own "page")
                         (dom/get-value)
                         (read-string))]
              (download-page-svg id)
              (udl/close!)))
          (download-zip [event]
            (dom/prevent-default event))
          (download-html [event]
            (dom/prevent-default event))]
    (let [pages (mx/react pages-ref)
          current (mx/react current-page-ref)]
      [:div.lightbox-body.export-dialog
       [:h3 "Export options"]
       [:div.row-flex
        [:div.content-col
         [:span.icon i/file-svg]
         [:span.title "Export page"]
         [:p.info "Download a single page of your project in SVG."]
         [:select.input-select {:ref "page" :default-value (pr-str current)}
          (for [{:keys [id name]} pages]
            [:option {:value (pr-str id)} name])]
         [:a.btn-primary {:href "#" :on-click download-page} "Export page"]]
        [:div.content-col
         [:span.icon i/folder-zip]
         [:span.title "Export project"]
         [:p.info "Download the whole project as a ZIP file."]
         [:a.btn-primary {:href "#" :on-click download-zip} "Expor project"]]
        [:div.content-col
         [:span.icon i/file-html]
         [:span.title "Export as HTML"]
         [:p.info "Download your project as HTML files."]
         [:a.btn-primary {:href "#" :on-click download-html} "Export HTML"]]]
       [:a.close {:href "#" :on-click on-close} i/close]])))

(defmethod lbx/render-lightbox :download
  [_]
  (download-dialog))
