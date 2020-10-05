;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.main.data.auth :refer [logout]]
   [app.main.data.messages :as dm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.auth :refer [auth]]
   [app.main.ui.auth.verify-token :refer [verify-token]]
   [app.main.ui.cursors :as c]
   [app.main.ui.dashboard :refer [dashboard]]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.main.ui.render :as render]
   [app.main.ui.settings :as settings]
   [app.main.ui.static :refer [not-found-page not-authorized-page]]
   [app.main.ui.viewer :refer [viewer-page]]
   [app.main.ui.workspace :as workspace]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.timers :as ts]
   [expound.alpha :as expound]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

;; --- Routes

(def routes
  [["/auth"
    ["/login" :auth-login]
    ["/register" :auth-register]
    ["/recovery/request" :auth-recovery-request]
    ["/recovery" :auth-recovery]
    ["/verify-token" :auth-verify-token]
    ["/goodbye" :auth-goodbye]]

   ["/settings"
    ["/profile" :settings-profile]
    ["/password" :settings-password]
    ["/options" :settings-options]]

   ["/view/:file-id/:page-id" :viewer]
   ["/not-found" :not-found]
   ["/not-authorized" :not-authorized]

   (when *assert*
     ["/debug/icons-preview" :debug-icons-preview])

   ;; Used for export
   ["/render-object/:file-id/:page-id/:object-id" :render-object]

   ["/dashboard/team/:team-id"
    ["/members" :dashboard-team-members]
    ["/settings" :dashboard-team-settings]
    ["/projects" :dashboard-projects]
    ["/search" :dashboard-search]
    ["/libraries" :dashboard-libraries]
    ["/projects/:project-id" :dashboard-files]]

   ["/workspace/:project-id/:file-id" :workspace]])

(mf/defc app-error
  [{:keys [error] :as props}]
  (let [data (ex-data error)]
    (case (:type data)
      :not-found [:& not-found-page {:error data}]
      (do
        (ptk/handle-error error)
        [:span "Internal application errror"]))))

(mf/defc app
  {::mf/wrap [#(mf/catch % {:fallback app-error})]}
  [{:keys [route] :as props}]
  (case (get-in route [:data :name])
    (:auth-login
     :auth-register
     :auth-goodbye
     :auth-recovery-request
     :auth-recovery)
    [:& auth {:route route}]

    :auth-verify-token
    [:& verify-token {:route route}]

    (:settings-profile
     :settings-password
     :settings-options)
    [:& settings/settings {:route route}]

    :debug-icons-preview
    (when *assert*
      [:div.debug-preview
       [:h1 "Cursors"]
       [:& c/debug-preview]
       [:h1 "Icons"]
       [:& i/debug-icons-preview]
       ])

    (:dashboard-search
     :dashboard-projects
     :dashboard-files
     :dashboard-libraries
     :dashboard-team-members
     :dashboard-team-settings)
    [:& dashboard {:route route}]

    :viewer
    (let [index (d/parse-integer (get-in route [:params :query :index]))
          token (get-in route [:params :query :token])
          file-id (uuid (get-in route [:params :path :file-id]))
          page-id (uuid (get-in route [:params :path :page-id]))]
      [:& viewer-page {:page-id page-id
                       :file-id file-id
                       :index index
                       :token token}])

    :render-object
    (do
      (let [file-id   (uuid (get-in route [:params :path :file-id]))
            page-id   (uuid (get-in route [:params :path :page-id]))
            object-id (uuid (get-in route [:params :path :object-id]))]
        [:& render/render-object {:file-id file-id
                                  :page-id page-id
                                  :object-id object-id}]))

    :workspace
    (let [project-id (uuid (get-in route [:params :path :project-id]))
          file-id (uuid (get-in route [:params :path :file-id]))
          page-id (uuid (get-in route [:params :query :page-id]))]
      [:& workspace/workspace {:project-id project-id
                               :file-id file-id
                               :page-id page-id
                               :key file-id}])

    :not-authorized
    [:& not-authorized-page]

    :not-found
    [:& not-found-page]

    nil))

(mf/defc app-wrapper
  []
  (let [route (mf/deref refs/route)]
    [:*
     [:& msgs/notifications]
     (when route
       [:& app {:route route}])]))

;; --- Error Handling

(defmethod ptk/handle-error :validation
  [error]
  (js/console.error "handle-error(validation):" (if (map? error) (pr-str error) error))
  (when-let [explain (:explain error)]
    (println "============ SERVER RESPONSE ERROR ================")
    (println explain)
    (println "============ END SERVER RESPONSE ERROR ================")))

(defmethod ptk/handle-error :authentication
  [error]
  (ts/schedule 0 #(st/emit! logout)))

(defmethod ptk/handle-error :assertion
  [{:keys [data stack] :as error}]
  (js/console.error stack)
  (js/console.error (with-out-str
                      (expound/printer data))))

(defmethod ptk/handle-error :default
  [error]
  (if (instance? ExceptionInfo error)
    (ptk/handle-error (ex-data error))
    (do
      (js/console.error "handle-error(default):"
                        (if (map? error) (pr-str error) error))
      (ts/schedule 100 #(st/emit! (dm/show {:content "Something wrong has happened."
                                            :type :error
                                            :timeout 5000}))))))

;; (defonce foo
;;   (do
;;     (prn "attach listener")
;;     (.addEventListener js/window "error" (fn [err] (ptk/handle-error (unchecked-get err "error"))))
;;     1))
