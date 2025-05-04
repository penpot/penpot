;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.static
  (:require-macros [app.main.style :as stl])
  (:require
   ["rxjs" :as rxjs]
   [app.common.data :as d]
   [app.common.pprint :as pp]
   [app.common.uri :as u]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.auth.login :refer [login-methods]]
   [app.main.ui.auth.recovery-request :refer [recovery-request-page recovery-sent-page]]
   [app.main.ui.auth.register :as register]
   [app.main.ui.dashboard.sidebar :refer [sidebar*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [app.main.ui.ds.foundations.assets.raw-svg :refer [raw-svg*]]
   [app.main.ui.icons :as i]
   [app.main.ui.viewer.header :as viewer.header]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

;; FIXME: this is a workaround until we export this class on beicon library
(def TimeoutError rxjs/TimeoutError)

(mf/defc error-container*
  {::mf/props :obj}
  [{:keys [children]}]
  (let [profile-id  (:profile-id @st/state)
        on-nav-root (mf/use-fn #(st/emit! (rt/nav-root)))]
    [:section {:class (stl/css :exception-layout)}
     [:button
      {:class (stl/css :exception-header)
       :on-click on-nav-root}
      [:> raw-svg* {:id "penpot-logo-icon" :class (stl/css :penpot-logo)}]
      (when profile-id
        [:div {:class (stl/css :go-back-wrapper)}
         [:> icon* {:icon-id "arrow" :class (stl/css :back-arrow)}] [:span (tr "not-found.no-permission.go-dashboard")]])]
     [:div {:class (stl/css :deco-before)} i/logo-error-screen]
     (when-not profile-id
       [:button {:class (stl/css :login-header)
                 :on-click on-nav-root}
        (tr "labels.login")])

     [:div {:class (stl/css :exception-content)}
      [:div {:class (stl/css :container)} children]]

     [:div {:class (stl/css :deco-after2)}
      [:span (tr "labels.copyright")]
      i/logo-error-screen
      [:span (tr "not-found.made-with-love")]]]))

(mf/defc invalid-token
  []
  [:> error-container* {}
   [:div {:class (stl/css :main-message)} (tr "errors.invite-invalid")]
   [:div {:class (stl/css :desc-message)} (tr "errors.invite-invalid.info")]])

(mf/defc login-dialog
  {::mf/props :obj}
  [{:keys [show-dialog]}]
  (let [current-section  (mf/use-state :login)
        user-email       (mf/use-state "")
        register-token   (mf/use-state "")

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
        (mf/use-fn
         (fn []
           (reset! show-dialog false)
           (st/emit! (rt/reload true))))

        success-register
        (mf/use-fn
         (fn [data]
           (reset! register-token (:token data))
           (reset! current-section :register-validate)))

        register-email-sent
        (mf/use-fn
         (fn [email]
           (reset! user-email email)
           (reset! current-section :register-email-sent)))

        recovery-email-sent
        (mf/use-fn
         (fn [email]
           (reset! user-email email)
           (reset! current-section :recovery-email-sent)))

        on-nav-root
        (mf/use-fn #(st/emit! (rt/nav-root)))]

    [:div {:class (stl/css :overlay)}
     [:div {:class (stl/css :dialog-login)}
      [:div {:class (stl/css :modal-close)}
       [:button {:class (stl/css :modal-close-button)
                 :on-click on-nav-root}
        i/close]]
      [:div {:class (stl/css :login)}
       [:div {:class (stl/css :logo)} i/logo]

       (case @current-section
         :login
         [:*
          [:div {:class (stl/css :logo-title)} (tr "labels.login")]
          [:div {:class (stl/css :logo-subtitle)} (tr "not-found.login.free")]
          [:& login-methods {:on-recovery-request set-section-recovery
                             :on-success-callback success-login
                             :params {:save-login-redirect true}}]
          [:hr {:class (stl/css :separator)}]
          [:div {:class (stl/css :change-section)}
           (tr "auth.register")
           " "
           [:a {:data-section "register"
                :on-click set-section}
            (tr "auth.register-submit")]]]

         :register
         [:*
          [:div {:class (stl/css :logo-title)} (tr "not-found.login.signup-free")]
          [:div {:class (stl/css :logo-subtitle)} (tr "not-found.login.start-using")]
          [:& register/register-methods {:on-success-callback success-register :hide-separator true}]
          #_[:hr {:class (stl/css :separator)}]
          [:div {:class (stl/css :separator)}]
          [:div {:class (stl/css :change-section)}
           (tr "auth.already-have-account")
           " "
           [:a {:data-section "login"
                :on-click set-section} (tr "auth.login-here")]]
          [:div {:class (stl/css :links)}
           [:hr {:class (stl/css :separator)}]
           [:& register/terms-register]]]

         :register-validate
         [:div {:class (stl/css :form-container)}
          [:& register/register-validate-form
           {:params {:token @register-token}
            :on-success-callback register-email-sent}]
          [:div {:class (stl/css :links)}
           [:div {:class (stl/css :register)}
            [:a {:data-section "register"
                 :on-click set-section}
             (tr "labels.go-back")]]]]

         :register-email-sent
         [:div {:class (stl/css :form-container)}
          [:& register/register-success-page {:params {:email @user-email :hide-logo true}}]]

         :recovery-request
         [:& recovery-request-page {:go-back-callback set-section-login
                                    :on-success-callback recovery-email-sent}]

         :recovery-email-sent
         [:div {:class (stl/css :form-container)}
          [:& recovery-sent-page {:email @user-email}]])]]]))

(mf/defc request-dialog
  {::mf/props :obj}
  [{:keys [title content button-text on-button-click cancel-text on-close]}]
  (let [on-click (or on-button-click on-close)]
    [:div {:class (stl/css :overlay)}
     [:div {:class (stl/css :dialog)}
      [:div {:class (stl/css :modal-close)}
       [:button {:class (stl/css :modal-close-button) :on-click on-close}
        i/close]]
      [:div {:class (stl/css :dialog-title)} title]
      (for [[index content] (d/enumerate content)]
        [:div {:key index} content])
      [:div {:class (stl/css :sign-info)}
       (when cancel-text
         [:button {:class (stl/css :cancel-button)
                   :on-click on-close}
          cancel-text])
       [:button {:on-click on-click} button-text]]]]))

(mf/defc request-access*
  [{:keys [file-id team-id is-default is-workspace]}]
  (let [profile     (mf/deref refs/profile)
        requested*  (mf/use-state {:sent false :already-requested false})
        requested   (deref requested*)
        show-dialog (mf/use-state true)

        on-close
        (mf/use-fn
         (mf/deps profile)
         (fn []
           (let [team-id (:default-team-id profile)]
             (st/emit! (dcm/go-to-dashboard-recent :team-id team-id)))))

        on-success
        (mf/use-fn
         #(reset! requested* {:sent true :already-requested false}))

        on-error
        (mf/use-fn
         #(reset! requested* {:sent true :already-requested true}))

        on-request-access
        (mf/use-fn
         (mf/deps file-id team-id is-workspace)
         (fn []
           (let [params (if (some? file-id)
                          {:file-id file-id
                           :is-viewer (not is-workspace)}
                          {:team-id team-id})
                 mdata  {:on-success on-success
                         :on-error on-error}]
             (st/emit! (dcm/create-team-access-request
                        (with-meta params mdata))))))]

    [:*
     (if (some? file-id)
       (if is-workspace
         [:div {:class (stl/css :workspace)}
          [:div {:class (stl/css :workspace-left)}
           i/logo-icon
           [:div
            [:div {:class (stl/css :project-name)} (tr "not-found.no-permission.project-name")]
            [:div {:class (stl/css :file-name)} (tr "not-found.no-permission.penpot-file")]]]
          [:div {:class (stl/css :workspace-right)}]]

         [:div {:class (stl/css :viewer)}
          ;; FIXME: the viewer header was never designed to be reused
          ;; from other parts of the application, and this code looks
          ;; like a fast workaround reusing it as-is without a proper
          ;; component adaptation for be able to use it easily it on
          ;; viewer context or static error page context
          [:& viewer.header/header {:project
                                    {:name (tr "not-found.no-permission.project-name")}
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
         [:> sidebar*
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
         [:& request-dialog {:title (tr "not-found.no-permission.project")
                             :button-text (tr "not-found.no-permission.go-dashboard")
                             :on-close on-close}]

         (and (some? file-id) (:already-requested requested))
         [:& request-dialog {:title (tr "not-found.no-permission.already-requested.file")
                             :content [(tr "not-found.no-permission.already-requested.or-others.file")]
                             :button-text (tr "not-found.no-permission.go-dashboard")
                             :on-close on-close}]

         (:already-requested requested)
         [:& request-dialog {:title (tr "not-found.no-permission.already-requested.project")
                             :content [(tr "not-found.no-permission.already-requested.or-others.project")]
                             :button-text (tr "not-found.no-permission.go-dashboard")
                             :on-close on-close}]

         (:sent requested)
         [:& request-dialog {:title (tr "not-found.no-permission.done.success")
                             :content [(tr "not-found.no-permission.done.remember")]
                             :button-text (tr "not-found.no-permission.go-dashboard")
                             :on-close on-close}]

         (some? file-id)
         [:& request-dialog {:title (tr "not-found.no-permission.file")
                             :content [(tr "not-found.no-permission.you-can-ask.file")
                                       (tr "not-found.no-permission.if-approves")]
                             :button-text (tr "not-found.no-permission.ask")
                             :on-button-click on-request-access
                             :cancel-text (tr "not-found.no-permission.go-dashboard")
                             :on-close on-close}]

         (some? team-id)
         [:& request-dialog {:title (tr "not-found.no-permission.project")
                             :content [(tr "not-found.no-permission.you-can-ask.project")
                                       (tr "not-found.no-permission.if-approves")]
                             :button-text (tr "not-found.no-permission.ask")
                             :on-button-click on-request-access
                             :cancel-text (tr "not-found.no-permission.go-dashboard")
                             :on-close on-close}]))]))

(mf/defc not-found*
  []
  [:> error-container* {}
   [:div {:class (stl/css :main-message)} (tr "labels.not-found.main-message")]
   [:div {:class (stl/css :desc-message)} (tr "not-found.desc-message.error")]
   [:div {:class (stl/css :desc-message)} (tr "not-found.desc-message.doesnt-exist")]])

(mf/defc bad-gateway*
  []
  (let [handle-retry
        (mf/use-fn
         (fn [] (st/emit! (rt/assign-exception nil))))]
    [:> error-container* {}
     [:div {:class (stl/css :main-message)} (tr "labels.bad-gateway.main-message")]
     [:div {:class (stl/css :desc-message)} (tr "labels.bad-gateway.desc-message")]
     [:div {:class (stl/css :sign-info)}
      [:button {:on-click handle-retry} (tr "labels.retry")]]]))

(mf/defc service-unavailable*
  []
  (let [on-click (mf/use-fn #(st/emit! (rt/assign-exception nil)))]
    [:> error-container* {}
     [:div {:class (stl/css :main-message)} (tr "labels.service-unavailable.main-message")]
     [:div {:class (stl/css :desc-message)} (tr "labels.service-unavailable.desc-message")]
     [:div {:class (stl/css :sign-info)}
      [:button {:on-click on-click} (tr "labels.retry")]]]))

(defn- generate-report
  [data]
  (try
    (let [team-id    (:current-team-id @st/state)
          profile-id (:profile-id @st/state)

          trace      (:app.main.errors/trace data)
          instance   (:app.main.errors/instance data)]
      (with-out-str
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

        (println)))
    (catch :default cause
      (.error js/console "error on generating report.txt" cause)
      nil)))

(mf/defc internal-error*
  [{:keys [on-reset report] :as props}]
  (let [report-uri (mf/use-ref nil)
        on-reset   (or on-reset #(st/emit! (rt/assign-exception nil)))

        on-download
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (when-let [uri (mf/ref-val report-uri)]
             (dom/trigger-download-uri "report" "text/plain" uri))))]

    (mf/with-effect [report]
      (when (some? report)
        (let [report (wapi/create-blob report "text/plain")
              uri    (wapi/create-uri report)]
          (mf/set-ref-val! report-uri uri)
          (fn []
            (wapi/revoke-uri uri)))))

    [:> error-container* {}
     [:div {:class (stl/css :main-message)} (tr "labels.internal-error.main-message")]
     [:div {:class (stl/css :desc-message)} (tr "labels.internal-error.desc-message")]
     (when (some? report)
       [:a {:on-click on-download} "Download report.txt"])
     [:div {:class (stl/css :sign-info)}
      [:button {:on-click on-reset} (tr "labels.retry")]]]))

(defn- load-info
  "Load exception page info"
  [path-params]
  (let [default {:loaded true}
        stream  (cond
                  (:file-id path-params)
                  (->> (rp/cmd! :get-file-info {:id (:file-id path-params)})
                       (rx/map (fn [info]
                                 {:loaded true
                                  :file-id (:id info)})))

                  (:team-id path-params)
                  (->> (rp/cmd! :get-team-info {:id (:team-id path-params)})
                       (rx/map (fn [info]
                                 {:loaded true
                                  :team-id (:id info)
                                  :team-default (:is-default info)})))

                  :else
                  (rx/of default))]

    (->> stream
         (rx/timeout 3000)
         (rx/catch (fn [cause]
                     (if (instance? TimeoutError cause)
                       (rx/of default)
                       (rx/throw cause)))))))

(mf/defc exception-section*
  {::mf/private true}
  [{:keys [data route] :as props}]
  (let [type   (get data :type)
        report (mf/with-memo [data]
                 (generate-report data))
        props  (mf/spread-props props {:report report})]

    (mf/with-effect [data route report]
      (let [params (:query-params route)
            params (u/map->query-string params)]
        (st/emit! (ptk/data-event ::ev/event
                                  {::ev/name "exception-page"
                                   :type (get data :type :unknown)
                                   :hint (get data :hint)
                                   :path (get route :path)
                                   :report report
                                   :params params}))))
    (case type
      :not-found
      [:> not-found* {}]

      :authentication
      [:> not-found* {}]

      :bad-gateway
      [:> bad-gateway* props]

      :service-unavailable
      [:> service-unavailable*]

      [:> internal-error* props])))

(mf/defc exception-page*
  {::mf/props :obj}
  [{:keys [data route] :as props}]

  (let [type       (:type data)
        path       (:path route)

        params     (:query-params route)

        workspace? (str/includes? path "workspace")
        dashboard? (str/includes? path "dashboard")
        view?      (str/includes? path "view")

        ;; We store the request access info int this state
        info*      (mf/use-state nil)
        info       (deref info*)

        loaded?    (get info :loaded false)

        request-access?
        (and
         (or (= type :not-found)
             (= type :authentication))
         (or workspace? dashboard? view?)
         (or (:file-id info)
             (:team-id info)))]

    (mf/with-effect [params info]
      (when-not (:loaded info)
        (->> (load-info params)
             (rx/subs! (partial reset! info*)
                       (partial reset! info* {:loaded true})))))

    (when loaded?
      (if request-access?
        [:> request-access* {:file-id (:file-id info)
                             :team-id  (:team-id info)
                             :is-default (:team-default info)
                             :is-workspace workspace?}]
        [:> exception-section* props]))))

