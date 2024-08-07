;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.static
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.pprint :as pp]
   [app.common.uri :as u]
   [app.main.data.common :as dc]
   [app.main.data.events :as ev]
   [app.main.store :as st]
   [app.main.ui.auth.login :refer [login-methods]]
   [app.main.ui.auth.recovery-request :refer [recovery-request-page recovery-sent-page]]
   [app.main.ui.auth.register :refer [register-methods register-validate-form register-success-page terms-register]]
   [app.main.ui.dashboard.sidebar :refer [sidebar]]
   [app.main.ui.icons :as i]
   [app.main.ui.viewer.header :as header]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.webapi :as wapi]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc error-container
  {::mf/wrap-props false}
  [{:keys [children]}]
  (let [profile-id (:profile-id @st/state)
        on-click (mf/use-fn #(set! (.-href globals/location) "/"))]
    [:section {:class (stl/css :exception-layout)}
     [:button
      {:class (stl/css :exception-header)
       :on-click on-click}
      i/logo-icon
      (when profile-id
        (str "< "
             (tr "not-found.no-permission.go-dashboard")))]
     [:div {:class (stl/css :deco-before)} i/logo-error-screen]
     (when-not profile-id
       [:button {:class (stl/css :login-header)
                 :on-click on-click}
        (tr "labels.login")])

     [:div {:class (stl/css :exception-content)}
      [:div {:class (stl/css :container)} children]]

     [:div {:class (stl/css :deco-after2)}
      [:span (tr "not-found.copyright")]
      i/logo-error-screen
      [:span (tr "not-found.made-with-love")]]]))

(mf/defc invalid-token
  []
  [:> error-container {}
   [:div {:class (stl/css :main-message)} (tr "errors.invite-invalid")]
   [:div {:class (stl/css :desc-message)} (tr "errors.invite-invalid.info")]])



(mf/defc login-dialog
  [{:keys [show-dialog]}]
  (let [current-section  (mf/use-state :login)
        user-email (mf/use-state "")
        register-token (mf/use-state "")
        on-close
        (mf/use-fn
         #(set! (.-href globals/location) "/"))

        set-section
        (mf/use-fn
         (fn [event]
           (let [section (-> (dom/get-current-target event)
                             (dom/get-data "section")
                             (keyword))]
             (reset! current-section section))))

        set-section-recovery
        (mf/use-fn
         #(reset! current-section :recovery-request))

        set-section-login
        (mf/use-fn
         #(reset! current-section :login))

        success-login
        (fn []
          (reset! show-dialog false)
          (.reload js/window.location true))

        success-register
        (fn [data]
          (reset! register-token (:token data))
          (reset! current-section :register-validate))

        register-email-sent
        (fn [email]
          (reset! user-email email)
          (reset! current-section :register-email-sent))

        recovery-email-sent
        (fn [email]
          (reset! user-email email)
          (reset! current-section :recovery-email-sent))]

    [:div {:class (stl/css :overlay)}
     [:div {:class (stl/css :dialog-login)}
      [:div {:class (stl/css :modal-close)}
       [:button {:class (stl/css :modal-close-button) :on-click on-close}
        i/close]]
      [:div {:class (stl/css :login)}
       [:div {:class (stl/css :logo)} i/logo]

       (case @current-section
         :login
         [:*
          [:div {:class (stl/css :logo-title)} (tr "not-found.login.login")]
          [:div {:class (stl/css :logo-subtitle)} (tr "not-found.login.free")]
          [:& login-methods {:on-recovery-request set-section-recovery
                             :on-success-callback success-login}]
          [:hr {:class (stl/css :separator)}]
          [:div {:class (stl/css :change-section)}
           (tr "auth.register")
           " "
           [:a {:data-section "register"
                :on-click set-section} (tr "auth.register-submit")]]]

         :register
         [:*
          [:div {:class (stl/css :logo-title)} (tr "not-found.login.signup-free")]
          [:div {:class (stl/css :logo-subtitle)} (tr "not-found.login.start-using")]
          [:& register-methods {:on-success-callback success-register :hide-separator true}]
          #_[:hr {:class (stl/css :separator)}]
          [:div {:class (stl/css :separator)}]
          [:div {:class (stl/css :change-section)}
           (tr "auth.already-have-account")
           " "
           [:a {:data-section "login"
                :on-click set-section} (tr "auth.login-here")]]
          [:div {:class (stl/css :links)}
           [:hr {:class (stl/css :separator)}]
           [:& terms-register]]]

         :register-validate
         [:div {:class (stl/css :form-container)}
          [:& register-validate-form {:params {:token @register-token}
                                      :on-success-callback register-email-sent}]
          [:div {:class (stl/css :links)}
           [:div {:class (stl/css :register)}
            [:a {:data-section "register"
                 :on-click set-section}
             (tr "labels.go-back")]]]]

         :register-email-sent
         [:div {:class (stl/css :form-container)}
          [:& register-success-page {:params {:email @user-email :hide-logo true}}]]

         :recovery-request
         [:& recovery-request-page {:go-back-callback set-section-login
                                    :on-success-callback recovery-email-sent}]

         :recovery-email-sent
         [:div {:class (stl/css :form-container)}
          [:& recovery-sent-page {:email @user-email}]])]]]))

(mf/defc request-dialog
  [{:keys [title content button-text on-button-click cancel-text] :as props}]
  (let [on-close (mf/use-fn #(set! (.-href globals/location) "/"))
        on-click (or on-button-click on-close)]
    [:div {:class (stl/css :overlay)}
     [:div {:class (stl/css :dialog)}
      [:div {:class (stl/css :modal-close)}
       [:button {:class (stl/css :modal-close-button) :on-click on-close}
        i/close]]
      [:div {:class (stl/css :dialog-title)} title]
      (for [txt content]
        [:div txt])
      [:div {:class (stl/css :sign-info)}
       (when cancel-text
         [:button {:class (stl/css :cancel-button) :on-click on-close} cancel-text])
       [:button {:on-click on-click} button-text]]]]))


(mf/defc request-access
  [{:keys [file-id team-id is-default workspace?] :as props}]
  (let [profile     (:profile @st/state)
        requested*  (mf/use-state {:sent false :already-requested false})
        requested   (deref requested*)
        show-dialog (mf/use-state true)
        on-success
        (mf/use-fn
         #(reset! requested* {:sent true :already-requested false}))
        on-error
        (mf/use-fn
         #(reset! requested* {:sent true :already-requested true}))
        on-request-access
        (mf/use-fn
         (mf/deps file-id)
         (fn []
           (let [params (if (str/empty? file-id) {:team-id team-id} {:file-id file-id :is-viewer (not workspace?)})
                 mdata  {:on-success on-success :on-error on-error}]
             (st/emit! (dc/create-team-request (with-meta params mdata))))))]


    [:*
     (if (not (str/empty? file-id))
       (if workspace?
         [:div {:class (stl/css :workspace)}
          [:div {:class (stl/css :workspace-left)}
           i/logo-icon
           [:div
            [:div {:class (stl/css :project-name)} (tr "not-found.no-permission.project-name")]
            [:div {:class (stl/css :file-name)} (tr "not-found.no-permission.penpot-file")]]]
          [:div {:class (stl/css :workspace-right)}]]
         [:div {:class (stl/css :viewer)}
          [:& header/header {:project {:name (tr "not-found.no-permission.project-name")}
                             :index 0
                             :file {:name (tr "not-found.no-permission.penpot-file")}
                             :page nil
                             :frame nil
                             :permissions {:is-logged true}
                             :zoom 1
                             :section :interactions
                             :shown-thumbnails false
                             :interactions-mode nil}]])

       [:div {:class (stl/css :dashboard)}
        [:div {:class (stl/css :dashboard-sidebar)}
         [:& sidebar
          {:team nil
           :projects []
           :project (:default-project-id profile)
           :profile profile
           :section :dashboard-projects
           :search-term ""}]]])

     (when @show-dialog
       (cond
         (nil? profile)
         [:& login-dialog {:show-dialog show-dialog}]

         is-default
         [:& request-dialog {:title (tr "not-found.no-permission.project") :button-text (tr "not-found.no-permission.go-dashboard")}]

         (and (not (str/empty? file-id)) (:already-requested requested))
         [:& request-dialog {:title (tr "not-found.no-permission.already-requested.file") :content [(tr "not-found.no-permission.already-requested.or-others.file")] :button-text (tr "not-found.no-permission.go-dashboard")}]

         (:already-requested requested)
         [:& request-dialog {:title (tr "not-found.no-permission.already-requested.project") :content [(tr "not-found.no-permission.already-requested.or-others.project")] :button-text (tr "not-found.no-permission.go-dashboard")}]

         (:sent requested)
         [:& request-dialog {:title (tr "not-found.no-permission.done.success") :content [(tr "not-found.no-permission.done.remember")] :button-text (tr "not-found.no-permission.go-dashboard")}]

         (not (str/empty? file-id))
         [:& request-dialog {:title (tr "not-found.no-permission.file") :content [(tr "not-found.no-permission.you-can-ask.file") (tr "not-found.no-permission.if-approves")] :button-text (tr "not-found.no-permission.ask") :on-button-click on-request-access :cancel-text (tr "not-found.no-permission.go-dashboard")}]

         (not (str/empty? team-id))
         [:& request-dialog {:title (tr "not-found.no-permission.project") :content [(tr "not-found.no-permission.you-can-ask.project") (tr "not-found.no-permission.if-approves")] :button-text (tr "not-found.no-permission.ask") :on-button-click on-request-access :cancel-text (tr "not-found.no-permission.go-dashboard")}]))]))



(mf/defc not-found
  []
  [:> error-container {}
   [:div {:class (stl/css :main-message)} (tr "labels.not-found.main-message")]
   [:div {:class (stl/css :desc-message)} (tr "not-found.desc-message.error")]
   [:div {:class (stl/css :desc-message)} (tr "not-found.desc-message.doesnt-exist")]])



(mf/defc bad-gateway
  []
  (let [handle-retry
        (mf/use-fn
         (fn [] (st/emit! (rt/assign-exception nil))))]
    [:> error-container {}
     [:div {:class (stl/css :main-message)} (tr "labels.bad-gateway.main-message")]
     [:div {:class (stl/css :desc-message)} (tr "labels.bad-gateway.desc-message")]
     [:div {:class (stl/css :sign-info)}
      [:button {:on-click handle-retry} (tr "labels.retry")]]]))

(mf/defc service-unavailable
  []
  (let [on-click (mf/use-fn #(st/emit! (rt/assign-exception nil)))]
    [:> error-container {}
     [:div {:class (stl/css :main-message)} (tr "labels.service-unavailable.main-message")]
     [:div {:class (stl/css :desc-message)} (tr "labels.service-unavailable.desc-message")]
     [:div {:class (stl/css :sign-info)}
      [:button {:on-click on-click} (tr "labels.retry")]]]))


(defn generate-report
  [data]
  (try
    (let [team-id    (:current-team-id @st/state)
          profile-id (:profile-id @st/state)

          trace      (:app.main.errors/trace data)
          instance   (:app.main.errors/instance data)
          content    (with-out-str
                       (println "Hint:   " (or (:hint data) (ex-message instance) "--"))
                       (println "Prof ID:" (str (or profile-id "--")))
                       (println "Team ID:" (str (or team-id "--")))

                       (when-let [file-id (:file-id data)]
                         (println "File ID:" (str file-id)))

                       (println)

                       (println "Data:")
                       (loop [data data]
                         (-> (d/without-qualified data)
                             (dissoc :explain)
                             (d/update-when :data (constantly "(...)"))
                             (pp/pprint {:level 8 :length 10}))

                         (println)

                         (when-let [explain (:explain data)]
                           (print explain))

                         (when (and (= :server-error (:type data))
                                    (contains? data :data))
                           (recur (:data data))))

                       (println "Trace:")
                       (println trace)
                       (println)

                       (println "Last events:")
                       (pp/pprint @st/last-events {:length 200})

                       (println))]
      (wapi/create-blob content "text/plain"))
    (catch :default err
      (.error js/console err)
      nil)))


(mf/defc internal-error
  {::mf/props :obj}
  [{:keys [data]}]
  (let [on-click   (mf/use-fn #(st/emit! (rt/assign-exception nil)))
        report-uri (mf/use-ref nil)
        report     (mf/use-memo (mf/deps data) #(generate-report data))

        on-download
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (when-let [uri (mf/ref-val report-uri)]
             (dom/trigger-download-uri "report" "text/plain" uri))))]

    (mf/with-effect [report]
      (when (some? report)
        (let [uri    (wapi/create-uri report)]
          (mf/set-ref-val! report-uri uri)
          (fn []
            (wapi/revoke-uri uri)))))

    [:> error-container {}
     [:div {:class (stl/css :main-message)} (tr "labels.internal-error.main-message")]
     [:div {:class (stl/css :desc-message)} (tr "labels.internal-error.desc-message")]
     (when (some? report)
       [:a {:on-click on-download} "Download report.txt"])
     [:div {:class (stl/css :sign-info)}
      [:button {:on-click on-click} (tr "labels.retry")]]]))

(mf/defc exception-page
  {::mf/props :obj}
  [{:keys [data route] :as props}]
  (let [file-info    (mf/use-state {:pending true})
        team-info    (mf/use-state {:pending true})
        type         (:type data)
        path         (:path route)

        workspace?   (str/includes? path "workspace")
        dashboard?   (str/includes? path "dashboard")
        view?        (str/includes? path "view")

        request-access? (and
                         (or workspace? dashboard? view?)
                         (or (not (str/empty? (:file-id @file-info))) (not (str/empty? (:team-id @team-info)))))

        query-params (u/map->query-string (:query-params route))
        pparams      (:path-params route)
        on-file-info (mf/use-fn
                      (fn [info]
                        (reset! file-info {:file-id (str (:id info))})))
        on-team-info (mf/use-fn
                      (fn [info]
                        (reset! team-info {:team-id (str (:id info)) :is-default (:is-default info)})))]
    (mf/with-effect
      (st/emit! (ptk/event ::ev/event {::ev/name "exception-page" :type type :path path :query-params query-params})
                (when (and (:file-id pparams) (:pending @file-info))
                  (dc/get-file-info on-file-info {:id (:file-id pparams)}))
                (when (and (:team-id pparams) (:pending @team-info))
                  (dc/get-team-info on-team-info {:id (:team-id pparams)}))))

    (case (:type data)
      :not-found
      (if request-access?
        [:& request-access {:file-id (:file-id @file-info) :team-id  (:team-id @team-info) :is-default (:is-default @team-info) :workspace? workspace?}]
        [:& not-found])

      :authentication
      (if request-access?
        [:& request-access {:file-id (:file-id @file-info) :team-id  (:team-id @team-info) :is-default (:is-default @team-info) :workspace? workspace?}]
        [:& not-found])

      :bad-gateway
      [:& bad-gateway]

      :service-unavailable
      [:& service-unavailable]

      [:> internal-error props])))
