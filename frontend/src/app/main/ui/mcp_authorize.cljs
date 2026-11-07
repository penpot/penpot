;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.mcp-authorize
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.uri :as uri]
   [app.config :as cf]
   [app.main.broadcast :as mbc]
   [app.main.data.profile :as profile]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.auth.login-modal :refer [login-register-modal*]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.typography :as typography]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.util.http :as http]
   [app.util.i18n :refer [tr]]
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(defn- endpoint
  [path]
  (str (uri/join cf/public-uri (str "api/mcp/oauth/" path))))

(defn- prepare-login!
  []
  (let [href (rt/get-current-href)]
    ;; Save the return URL even when only SSO buttons are enabled and the
    ;; password form (which normally stores it) never mounts.
    (binding [storage/*sync* true]
      (swap! storage/session assoc :login-redirect href))
    {:callback-url href}))

(defn- cancel-login!
  []
  ;; Do not let a later, unrelated login resume this cancelled attempt.
  (when (= (:login-redirect storage/session) (rt/get-current-href))
    (binding [storage/*sync* true]
      (swap! storage/session dissoc :login-redirect))))

(defn- oauth-request
  [method path params]
  (->> (http/send! (cond-> {:method method
                            :uri (endpoint path)
                            :response-type :json
                            :omit-default-headers true}
                     (= method :get) (assoc :query params)
                     (= method :post) (assoc :headers {"content-type" "application/x-www-form-urlencoded"}
                                             :body (uri/map->query-string params))))
       (rx/map #(update % :body js->clj :keywordize-keys true))))

(mf/defc client-logo*
  {::mf/private true}
  [{:keys [uri]}]
  (let [failed (mf/use-state false)]
    (when-not @failed
      [:img {:class (stl/css :client-logo)
             :src uri
             :alt ""
             :referrer-policy "no-referrer"
             :on-error #(reset! failed true)}])))

(mf/defc mcp-authorize*
  [{:keys [route profile]}]
  (let [handle     (get-in route [:query-params :request])
        details    (mf/use-state nil)
        failed?    (mf/use-state false)
        login-params (mf/use-state nil)
        cancelled? (mf/use-state false)
        busy?      (mf/use-state false)
        ;; The ref also blocks a second click before React renders disabled buttons.
        submitting (mf/use-memo (fn [] (atom false)))

        decide!
        (mf/use-fn
         (mf/deps handle @details)
         (fn [decision]
           (when (compare-and-set! submitting false true)
             (reset! busy? true)
             (->> (oauth-request :post "decision"
                                 {:request handle :csrf (:csrf @details) :decision decision})
                  (rx/subs!
                   (fn [{:keys [status body]}]
                     (if (= status 200)
                       (do
                         (when (= decision "approve")
                           (st/emit! (profile/refresh-profile)
                                     (mbc/event :mcp/enable {})))
                         (st/emit! (rt/nav-raw :href (:redirect-uri body))))
                       (reset! failed? true)))
                   (fn [_] (reset! failed? true)))))))]

    (mf/with-effect [handle]
      (reset! details nil)
      (reset! failed? false)
      (reset! login-params nil)
      (reset! cancelled? false)
      (reset! busy? false)
      (reset! submitting false)
      (let [subscription
            (->> (oauth-request :get "request" {:request handle})
                 (rx/subs!
                  (fn [{:keys [status body]}]
                    (case status
                      200 (reset! details body)
                      401 (reset! login-params (prepare-login!))
                      (reset! failed? true)))
                  (fn [_] (reset! failed? true))))]
        #(rx/dispose! subscription)))

    [:main {:class (stl/css :page)}
     (if @login-params
       [:> login-register-modal*
        {:params @login-params
         :on-register #(st/emit! (rt/reload true))
         :on-close (fn []
                     (cancel-login!)
                     (reset! login-params nil)
                     (reset! cancelled? true))}]
       [:section {:class (stl/css :card) :aria-labelledby "mcp-approval-title"}
        [:> heading* {:level 1 :typography typography/title-large :id "mcp-approval-title"}
         (tr "mcp.oauth.title")]
        (cond
          @cancelled?
          [:> text* {:typography typography/body-large :role "status"}
           (tr "mcp.oauth.cancelled")]

          @failed?
          [:> text* {:typography typography/body-large :role "alert"}
           (tr "mcp.oauth.failed")]

          (nil? @details)
          [:> loader* {:title (tr "labels.loading")}]

          :else
          [:*
           [:div {:class (stl/css :client)}
            (when-let [logo-uri (:logo-uri @details)]
              [:> client-logo* {:key logo-uri :uri logo-uri}])
            [:div {:class (stl/css :client-details)}
             [:> text* {:typography typography/body-large :class (stl/css :client-text)}
              (tr "mcp.oauth.request" (or (:client-name @details) (:client-id @details)))]
             [:> text* {:typography typography/body-small :class (stl/css :client-text)}
              (:client-id @details)]]]
           [:> text* {:typography typography/body-medium}
            (tr "mcp.oauth.account" (:email profile))]
           [:> text* {:typography typography/body-large}
            (tr "mcp.oauth.access")]
           [:> text* {:typography typography/body-medium}
            (tr "mcp.oauth.enable")]
           [:div {:class (stl/css :actions)}
            [:> button* {:variant "secondary" :disabled @busy? :on-click #(decide! "deny")}
             (tr "labels.cancel")]
            [:> button* {:variant "primary" :disabled @busy? :on-click #(decide! "approve")}
             (tr "mcp.oauth.approve")]]])])]))
