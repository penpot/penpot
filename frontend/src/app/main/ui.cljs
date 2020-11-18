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
   [app.config :as cfg]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.common.spec :as us]
   [app.main.data.auth :refer [logout]]
   [app.main.data.messages :as dm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.auth :refer [auth]]
   [app.main.ui.auth.verify-token :refer [verify-token]]
   [app.main.ui.cursors :as c]
   [app.main.ui.context :as ctx]
   [app.main.ui.dashboard :refer [dashboard]]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.main.ui.render :as render]
   [app.main.ui.settings :as settings]
   [app.main.ui.static :refer [not-found-page not-authorized-page]]
   [app.main.ui.viewer :refer [viewer-page]]
   [app.main.ui.handoff :refer [handoff]]
   [app.main.ui.workspace :as workspace]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.timers :as ts]
   [cuerdas.core :as str]
   [cljs.spec.alpha :as s]
   [expound.alpha :as expound]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

;; --- Routes

(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::viewer-path-params
  (s/keys :req-un [::file-id ::page-id]))

(s/def ::section ::us/keyword)
(s/def ::index ::us/integer)
(s/def ::token (s/nilable ::us/string))

(s/def ::viewer-query-params
  (s/keys :req-un [::index]
          :opt-un [::token ::section]))

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

   ["/view/:file-id/:page-id"
    {:name :viewer
     :conform
     {:path-params ::viewer-path-params
      :query-params ::viewer-query-params}}]

   ["/handoff/:file-id/:page-id"
    {:name :handoff
     :conform {:path-params ::viewer-path-params
               :query-params ::viewer-query-params}}]

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

  [:& (mf/provider ctx/current-route) {:value route}
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
     (let [index   (get-in route [:query-params :index])
           token   (get-in route [:query-params :token])
           section (get-in route [:query-params :section] :interactions)
           file-id (get-in route [:path-params :file-id])
           page-id (get-in route [:path-params :page-id])]
       [:& viewer-page {:page-id page-id
                        :file-id file-id
                        :section section
                        :index index
                        :token token}])

     :handoff
     (let [file-id (get-in route [:path-params :file-id])
           page-id (get-in route [:path-params :page-id])
           index   (get-in route [:query-params :index])]
       [:& handoff {:page-id page-id
                    :file-id file-id
                    :index index}])

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
           page-id (uuid (get-in route [:params :query :page-id]))
           layout-name (get-in route [:params :query :layout])]
       [:& workspace/workspace {:project-id project-id
                                :file-id file-id
                                :page-id page-id
                                :layout-name (keyword layout-name)
                                :key file-id}])

     :not-authorized
     [:& not-authorized-page]

     :not-found
     [:& not-found-page]

     nil)])

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
  (ts/schedule
   (st/emitf (dm/show {:content "Unexpected validation error (server side)."
                       :type :error
                       :timeout 5000})))
  (when-let [explain (:explain error)]
    (js/console.group "Server Error")
    (js/console.error (if (map? error) (pr-str error) error))
    (js/console.error explain)
    (js/console.endGroup "Server Error")))


(defmethod ptk/handle-error :authentication
  [error]
  (ts/schedule 0 #(st/emit! (logout))))

(defmethod ptk/handle-error :authorization
  [error]
  (ts/schedule
   (st/emitf (dm/show {:content "Not authorized to see this content."
                       :type :error}))))

(defmethod ptk/handle-error :assertion
  [{:keys [data stack message context] :as error}]
  (js/console.group message)
  (js/console.info (str/format "ns: '%s'\nname: '%s'\nfile: '%s:%s'"
                                (:ns context)
                                (:name context)
                                (str cfg/public-uri "/js/cljs-runtime/" (:file context))
                                (:line context)))
  (js/console.groupCollapsed "Stack Trace")
  (js/console.info stack)
  (js/console.groupEnd "Stack Trace")

  (js/console.error (with-out-str (expound/printer data)))
  (js/console.groupEnd message))

(defmethod ptk/handle-error :default
  [error]
  (js/console.log error)
  (if (instance? ExceptionInfo error)
    (ptk/handle-error (ex-data error))
    (do
      (js/console.group "Generic Error:")
      (ex/ignoring
       (js/console.error (pr-str error))
       (js/console.error (.-stack error)))
      (js/console.groupEnd "Generic error")
      (ts/schedule (st/emitf (dm/show
                              {:content "Something wrong has happened."
                               :type :error
                               :timeout 5000}))))))

(defmethod ptk/handle-error :server-error
  [{:keys [status] :as error}]
  (cond
    (= status 429)
    (ts/schedule
     (st/emitf (dm/show {:content "Too many requests, wait a little bit and retry."
                         :type :error
                         :timeout 5000})))

    :else
    (ts/schedule
     (st/emitf (dm/show {:content "Unable to connect to backend, wait a little bit and refresh."
                         :type :error})))))


(defmethod ptk/handle-error :not-found
  [{:keys [status] :as error}]
  (ts/schedule
   (st/emitf (dm/show {:content "Resource not found."
                       :type :warning}))))

(defonce uncaught-error-handler
  (letfn [(on-error [event]
            (ptk/handle-error (unchecked-get event "error"))
            (.preventDefault ^js event))]
    (.addEventListener js/window "error" on-error)
    (fn []
      (.removeEventListener js/window "error" on-error))))
