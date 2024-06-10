;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.api
  "RPC for plugins runtime."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.geom.point :as gpt]
   [app.common.record :as cr]
   [app.common.text :as txt]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as ch]
   [app.main.data.workspace.bool :as dwb]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.media :as dwm]
   [app.main.store :as st]
   [app.plugins.events :as events]
   [app.plugins.file :as file]
   [app.plugins.library :as library]
   [app.plugins.page :as page]
   [app.plugins.shape :as shape]
   [app.plugins.user :as user]
   [app.plugins.utils :as utils]
   [app.plugins.viewport :as viewport]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [promesa.core :as p]))

;;
;; PLUGINS PUBLIC API - The plugins will able to access this functions
;;
(defn create-shape
  [plugin-id type]
  (let [page-id (:current-page-id @st/state)
        page (dm/get-in @st/state [:workspace-data :pages-index page-id])
        shape (cts/setup-shape {:type type
                                :x 0 :y 0 :width 100 :height 100})
        changes
        (-> (cb/empty-changes)
            (cb/with-page page)
            (cb/with-objects (:objects page))
            (cb/add-object shape))]
    (st/emit! (ch/commit-changes changes))
    (shape/shape-proxy plugin-id (:id shape))))

(deftype PenpotContext [$plugin]
  Object
  (addListener
    [_ type callback]
    (events/add-listener type $plugin callback))

  (removeListener
    [_ listener-id]
    (events/remove-listener listener-id))

  (getViewport
    [_]
    (viewport/create-proxy $plugin))

  (getFile
    [_]
    (file/file-proxy $plugin (:current-file-id @st/state)))

  (getPage
    [_]
    (let [file-id (:current-file-id @st/state)
          page-id (:current-page-id @st/state)]
      (page/page-proxy $plugin file-id page-id)))

  (getSelected
    [_]
    (let [selection (get-in @st/state [:workspace-local :selected])]
      (apply array (map str selection))))

  (getSelectedShapes
    [_]
    (let [selection (get-in @st/state [:workspace-local :selected])]
      (apply array (sequence (map (partial shape/shape-proxy $plugin)) selection))))

  (getRoot
    [_]
    (shape/shape-proxy $plugin uuid/zero))

  (getTheme
    [_]
    (let [theme (get-in @st/state [:profile :theme])]
      (if (or (not theme) (= theme "default"))
        "dark"
        (get-in @st/state [:profile :theme]))))

  (getCurrentUser
    [_]
    (user/current-user-proxy $plugin (:session-id @st/state)))

  (getActiveUsers
    [_]
    (apply array
           (->> (:workspace-presence @st/state)
                (vals)
                (remove #(= (:id %) (:session-id @st/state)))
                (map #(user/active-user-proxy $plugin (:id %))))))

  (uploadMediaUrl
    [_ name url]
    (let [file-id (:current-file-id @st/state)]
      (p/create
       (fn [resolve reject]
         (->> (dwm/upload-media-url name file-id url)
              (rx/map utils/to-js)
              (rx/take 1)
              (rx/subs! resolve reject))))))

  (group
    [_ shapes]
    (let [file-id (:current-file-id @st/state)
          page-id (:current-page-id @st/state)
          id (uuid/next)
          ids (into #{} (map #(obj/get % "$id")) shapes)]
      (st/emit! (dwg/group-shapes id ids))
      (shape/shape-proxy $plugin file-id page-id id)))

  (ungroup
    [_ group & rest]
    (let [shapes (concat [group] rest)
          ids (into #{} (map #(obj/get % "$id")) shapes)]
      (st/emit! (dwg/ungroup-shapes ids))))

  (createFrame
    [_]
    (create-shape $plugin :frame))

  (createRectangle
    [_]
    (create-shape $plugin :rect))

  (createEllipse
    [_]
    (create-shape $plugin :circle))

  (createPath
    [_]
    (let [page-id (:current-page-id @st/state)
          page (dm/get-in @st/state [:workspace-data :pages-index page-id])
          shape (cts/setup-shape
                 {:type :path
                  :content [{:command :move-to :params {:x 0 :y 0}}
                            {:command :line-to :params {:x 100 :y 100}}]})
          changes
          (-> (cb/empty-changes)
              (cb/with-page page)
              (cb/with-objects (:objects page))
              (cb/add-object shape))]
      (st/emit! (ch/commit-changes changes))
      (shape/shape-proxy $plugin (:id shape))))

  (createText
    [_ text]
    (let [file-id (:current-file-id @st/state)
          page-id (:current-page-id @st/state)
          page (dm/get-in @st/state [:workspace-data :pages-index page-id])
          shape (-> (cts/setup-shape {:type :text :x 0 :y 0 :grow-type :auto-width})
                    (txt/change-text text)
                    (assoc :position-data nil))
          changes
          (-> (cb/empty-changes)
              (cb/with-page page)
              (cb/with-objects (:objects page))
              (cb/add-object shape))]
      (st/emit! (ch/commit-changes changes))
      (shape/shape-proxy $plugin file-id page-id (:id shape))))

  (createShapeFromSvg
    [_ svg-string]
    (when (some? svg-string)
      (let [id (uuid/next)
            file-id (:current-file-id @st/state)
            page-id (:current-page-id @st/state)]
        (st/emit! (dwm/create-svg-shape id "svg" svg-string (gpt/point 0 0)))
        (shape/shape-proxy $plugin file-id page-id id))))

  (createBoolean [_ bool-type shapes]
    (let [ids (into #{} (map #(obj/get % "$id")) shapes)
          bool-type (keyword bool-type)]

      (if (contains? cts/bool-types bool-type)
        (let [id-ret (atom nil)]
          (st/emit! (dwb/create-bool bool-type ids {:id-ret id-ret}))
          (shape/shape-proxy $plugin @id-ret))

        (utils/display-not-valid :bool-shape bool-type)))))

(defn create-context
  [plugin-id]
  (cr/add-properties!
   (PenpotContext. plugin-id)
   {:name "$plugin" :enumerable false :get (constantly plugin-id)}
   {:name "root" :get #(.getRoot ^js %)}
   {:name "currentFile" :get #(.getFile ^js %)}
   {:name "currentPage" :get #(.getPage ^js %)}
   {:name "selection" :get #(.getSelectedShapes ^js %)}
   {:name "viewport" :get #(.getViewport ^js %)}
   {:name "currentUser" :get #(.getCurrentUser ^js %)}
   {:name "activeUsers" :get #(.getActiveUsers ^js %)}
   {:name "library" :get (fn [_] (library/library-subcontext plugin-id))}))
