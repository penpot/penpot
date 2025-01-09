;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.email
  "Main api for send emails."
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.pprint :as pp]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.util.template :as tmpl]
   [app.worker :as wrk]
   [clojure.java.io :as io]
   [cuerdas.core :as str]
   [integrant.core :as ig])
  (:import
   jakarta.mail.Message$RecipientType
   jakarta.mail.Session
   jakarta.mail.Transport
   jakarta.mail.internet.InternetAddress
   jakarta.mail.internet.MimeBodyPart
   jakarta.mail.internet.MimeMessage
   jakarta.mail.internet.MimeMultipart
   java.util.Properties))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EMAIL IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- parse-address
  ^"[Ljakarta.mail.internet.InternetAddress;"
  [v]
  (InternetAddress/parse ^String v))

(defn- resolve-recipient-type
  ^Message$RecipientType
  [type]
  (case type
    :to  Message$RecipientType/TO
    :cc  Message$RecipientType/CC
    :bcc Message$RecipientType/BCC))

(defn- assign-recipient
  [^MimeMessage mmsg type address]
  (if (sequential? address)
    (reduce #(assign-recipient %1 type %2) mmsg address)
    (let [address (parse-address address)
          type    (resolve-recipient-type type)]
      (.addRecipients mmsg type address)
      mmsg)))
(defn- assign-recipients
  [mmsg {:keys [to cc bcc] :as params}]
  (cond-> mmsg
    (some? to)  (assign-recipient :to to)
    (some? cc)  (assign-recipient :cc cc)
    (some? bcc) (assign-recipient :bcc bcc)))

(defn- assign-from
  [mmsg {:keys [::default-from] :as cfg} {:keys [from] :as params}]
  (let [from (or from default-from)]
    (when from
      (let [from (parse-address from)]
        (.addFrom ^MimeMessage mmsg from)))))

(defn- assign-reply-to
  [mmsg {:keys [::default-reply-to] :as cfg} {:keys [reply-to] :as params}]
  (let [reply-to (or reply-to default-reply-to)]
    (when reply-to
      (let [reply-to (parse-address reply-to)]
        (.setReplyTo ^MimeMessage mmsg reply-to)))))

(defn- assign-subject
  [mmsg {:keys [subject charset] :or {charset "utf-8"} :as params}]
  (assert (string? subject) "subject is mandatory")
  (.setSubject ^MimeMessage mmsg
               ^String subject
               ^String charset))

(defn- assign-extra-headers
  [^MimeMessage mmsg {:keys [headers extra-data] :as params}]
  (let [headers (assoc headers "X-Penpot-Data" extra-data)]
    (reduce-kv (fn [^MimeMessage mmsg k v]
                 (doto mmsg
                   (.addHeader (name k) (str v))))
               mmsg
               headers)))

(defn- assign-body
  [^MimeMessage mmsg {:keys [body charset] :or {charset "utf-8"}}]
  (let [mpart (MimeMultipart. "mixed")]
    (cond
      (string? body)
      (let [bpart (MimeBodyPart.)]
        (.setContent bpart ^String body (str "text/plain; charset=" charset))
        (.addBodyPart mpart bpart))

      (vector? body)
      (let [mmp (MimeMultipart. "alternative")
            mbp (MimeBodyPart.)]
        (.addBodyPart mpart mbp)
        (.setContent mbp mmp)
        (doseq [item body]
          (let [mbp (MimeBodyPart.)]
            (.setContent mbp
                         ^String (:content item)
                         ^String (str (:type item "text/plain") "; charset=" charset))
            (.addBodyPart mmp mbp))))

      (map? body)
      (let [bpart (MimeBodyPart.)]
        (.setContent bpart
                     ^String (:content body)
                     ^String (str (:type body "text/plain") "; charset=" charset))
        (.addBodyPart mpart bpart))

      :else
      (throw (ex-info "Unsupported type" {:body body})))
    (.setContent mmsg mpart)
    mmsg))

(defn- opts->props
  [{:keys [::username ::tls ::host ::port ::timeout ::default-from]
    :or {timeout 30000}}]
  (reduce-kv
   (fn [^Properties props k v]
     (if (nil? v)
       props
       (doto props (.put ^String k  ^String (str v)))))
   (Properties.)
   {"mail.user" username
    "mail.host" host
    "mail.debug" (contains? cf/flags :smtp-debug)
    "mail.from" default-from
    "mail.smtp.auth" (boolean username)
    "mail.smtp.starttls.enable" tls
    "mail.smtp.starttls.required" tls
    "mail.smtp.host" host
    "mail.smtp.port" port
    "mail.smtp.user" username
    "mail.smtp.timeout" timeout
    "mail.smtp.connectiontimeout" timeout}))

(def ^:private schema:smtp-config
  [:map
   [::username {:optional true} :string]
   [::password {:optional true} :string]
   [::tls {:optional true} ::sm/boolean]
   [::ssl {:optional true} ::sm/boolean]
   [::host {:optional true} :string]
   [::port {:optional true} ::sm/int]
   [::default-from {:optional true} :string]
   [::default-reply-to {:optional true} :string]])

(def valid-smtp-config?
  (sm/check-fn schema:smtp-config))

(defn- create-smtp-session
  ^Session
  [cfg]
  (dm/assert!
   "expected valid smtp config"
   (valid-smtp-config? cfg))

  (let [props (opts->props cfg)]
    (Session/getInstance props)))

(defn- create-smtp-message
  ^MimeMessage
  [cfg session params]
  (let [mmsg (MimeMessage. ^Session session)]
    (assign-recipients mmsg params)
    (assign-from mmsg cfg params)
    (assign-reply-to mmsg cfg params)
    (assign-subject mmsg params)
    (assign-extra-headers mmsg params)
    (assign-body mmsg params)
    (.saveChanges ^MimeMessage mmsg)
    mmsg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TEMPLATE EMAIL IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private email-path "app/email/%(id)s/%(lang)s.%(type)s")

(defn- render-email-template-part
  [type id context]
  (let [lang (:lang context :en)
        path (str/format email-path {:id (name id)
                                     :lang (name lang)
                                     :type (name type)})]
    (some-> (io/resource path)
            (tmpl/render context))))

(defn- build-email-template
  [id context]
  (let [subj (render-email-template-part :subj id context)
        text (render-email-template-part :txt id context)
        html (render-email-template-part :html id context)]
    (when (or (not subj)
              (and (not text)
                   (not html)))
      (ex/raise :type :internal
                :code :missing-email-templates))
    {:subject subj
     :body (into
            [{:type "text/plain"
              :content text}]
            (when html
              [{:type "text/html"
                :content html}]))}))

(def ^:private schema:context
  [:map
   [:to [:or ::sm/email [::sm/vec ::sm/email]]]
   [:reply-to {:optional true} ::sm/email]
   [:from {:optional true} ::sm/email]
   [:lang {:optional true} ::sm/text]
   [:priority {:optional true} [:enum :high :low]]
   [:extra-data {:optional true} ::sm/text]])

(def ^:private check-context
  (sm/check-fn schema:context))

(defn template-factory
  [& {:keys [id schema]}]
  (assert (keyword? id) "id should be provided and it should be a keyword")
  (let [check-fn (if schema
                   (sm/check-fn schema)
                   (constantly nil))]
    (fn [context]
      (let [context (-> context check-context check-fn)
            email   (build-email-template id context)]
        (when-not email
          (ex/raise :type :internal
                    :code :email-template-does-not-exists
                    :hint "seems like the template is wrong or does not exists."
                    :template-id id))

        (cond-> (assoc email :id (name id))
          (:extra-data context)
          (assoc :extra-data (:extra-data context))

          (:from context)
          (assoc :from (:from context))

          (:reply-to context)
          (assoc :reply-to (:reply-to context))

          (:to context)
          (assoc :to (:to context)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC HIGH-LEVEL API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render
  [email-factory context]
  (email-factory context))

(defn send!
  "Schedule an already defined email to be sent using asynchronously
  using worker task."
  [{:keys [::conn ::factory] :as context}]
  (assert (db/connectable? conn) "expected a valid database connection or pool")

  (let [email (if factory
                (factory context)
                (dissoc context ::conn))]
    (wrk/submit! {::wrk/task :sendmail
                  ::wrk/delay 0
                  ::wrk/max-retries 4
                  ::wrk/priority 200
                  ::db/conn conn
                  ::wrk/params email})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SENDMAIL FN / TASK HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare send-to-logger!)

(defmethod ig/init-key ::sendmail
  [_ cfg]
  (fn [params]
    (when (contains? cf/flags :smtp)
      (let [session (create-smtp-session cfg)]
        (with-open [transport (.getTransport session (if (::ssl cfg) "smtps" "smtp"))]
          (.connect ^Transport transport
                    ^String (::host cfg)
                    ^String (::port cfg)
                    ^String (::username cfg)
                    ^String (::password cfg))

          (let [^MimeMessage message (create-smtp-message cfg session params)]
            (l/dbg :hint "sendmail"
                   :id (:id params)
                   :to (:to params)
                   :subject (str/trim (:subject params)))

            (.sendMessage ^Transport transport
                          ^MimeMessage message
                          (.getAllRecipients message))))))

    (when (contains? cf/flags :log-emails)
      (send-to-logger! cfg params))))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (fn? (::sendmail params)) "expected valid sendmail handler"))

(defmethod ig/init-key ::handler
  [_ {:keys [::sendmail]}]
  (fn [{:keys [props] :as task}]
    (sendmail props)))

(defn- send-to-logger!
  [_ email]
  (let [body (:body email)
        out  (with-out-str
               (println "email console dump:")
               (println "******** start email" (:id email) "**********")
               (pp/pprint (dissoc email :body))
               (if (string? body)
                 (println body)
                 (println (->> body
                               (filter #(= "text/plain" (:type %)))
                               (map :content)
                               first)))
               (println "******** end email" (:id email) "**********"))]
    (l/raw! :info out)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EMAIL FACTORIES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:feedback
  [:map
   [:subject ::sm/text]
   [:content ::sm/text]])

(def user-feedback
  "A profile feedback email."
  (template-factory
   :id ::feedback
   :schema schema:feedback))

(def ^:private schema:register
  [:map [:name ::sm/text]])

(def register
  "A new profile registration welcome email."
  (template-factory
   :id ::register
   :schema schema:register))

(def ^:private schema:password-recovery
  [:map
   [:name ::sm/text]
   [:token ::sm/text]])

(def password-recovery
  "A password recovery notification email."
  (template-factory
   :id ::password-recovery
   :schema schema:password-recovery))

(def ^:private schema:change-email
  [:map
   [:name ::sm/text]
   [:pending-email ::sm/email]
   [:token ::sm/text]])

(def change-email
  "Password change confirmation email"
  (template-factory
   :id ::change-email
   :schema schema:change-email))

(def ^:private schema:invite-to-team
  [:map
   [:invited-by ::sm/text]
   [:team ::sm/text]
   [:token ::sm/text]])

(def invite-to-team
  "Teams member invitation email."
  (template-factory
   :id ::invite-to-team
   :schema schema:invite-to-team))

(def ^:private schema:join-team
  [:map
   [:invited-by ::sm/text]
   [:team ::sm/text]
   [:team-id ::sm/uuid]])

(def join-team
  "Teams member joined after request email."
  (template-factory
   :id ::join-team
   :schema schema:join-team))

(def ^:private schema:request-file-access
  [:map
   [:requested-by ::sm/text]
   [:requested-by-email ::sm/text]
   [:team-name ::sm/text]
   [:team-id ::sm/uuid]
   [:file-name ::sm/text]
   [:file-id ::sm/uuid]
   [:page-id ::sm/uuid]])

(def request-file-access
  "File access request email."
  (template-factory
   :id ::request-file-access
   :schema schema:request-file-access))

(def request-file-access-yourpenpot
  "File access on Your Penpot request email."
  (template-factory
   :id ::request-file-access-yourpenpot
   :schema schema:request-file-access))

(def request-file-access-yourpenpot-view
  "File access on Your Penpot view mode request email."
  (template-factory
   :id ::request-file-access-yourpenpot-view
   :schema schema:request-file-access))

(def ^:private schema:request-team-access
  [:map
   [:requested-by ::sm/text]
   [:requested-by-email ::sm/text]
   [:team-name ::sm/text]
   [:team-id ::sm/uuid]])

(def request-team-access
  "Team access request email."
  (template-factory
   :id ::request-team-access
   :schema schema:request-team-access))

(def ^:private schema:comment-mention
  [:map
   [:name ::sm/text]
   [:source-user ::sm/text]
   [:comment-reference ::sm/text]
   [:comment-content ::sm/text]
   [:comment-url ::sm/text]])

(def comment-mention
  (template-factory
   :id ::comment-mention
   :schema schema:comment-mention))

(def ^:private schema:comment-thread
  [:map
   [:name ::sm/text]
   [:source-user ::sm/text]
   [:comment-reference ::sm/text]
   [:comment-content ::sm/text]
   [:comment-url ::sm/text]])

(def comment-thread
  (template-factory
   :id ::comment-thread
   :schema schema:comment-thread))

(def ^:private schema:comment-notification
  [:map
   [:name ::sm/text]
   [:source-user ::sm/text]
   [:comment-reference ::sm/text]
   [:comment-content ::sm/text]
   [:comment-url ::sm/text]])

(def comment-notification
  (template-factory
   :id ::comment-notification
   :schema schema:comment-notification))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; BOUNCE/COMPLAINS HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sql:profile-complaint-report
  "select (select count(*)
             from profile_complaint_report
            where type = 'complaint'
              and profile_id = ?
              and created_at > now() - ?::interval) as complaints,
          (select count(*)
             from profile_complaint_report
            where type = 'bounce'
              and profile_id = ?
              and created_at > now() - ?::interval) as bounces;")

(defn allow-send-emails?
  [conn profile]
  (when-not (:is-muted profile false)
    (let [complaint-threshold (cf/get :profile-complaint-threshold)
          complaint-max-age   (cf/get :profile-complaint-max-age)
          bounce-threshold    (cf/get :profile-bounce-threshold)
          bounce-max-age      (cf/get :profile-bounce-max-age)

          {:keys [complaints bounces] :as result}
          (db/exec-one! conn [sql:profile-complaint-report
                              (:id profile)
                              (db/interval complaint-max-age)
                              (:id profile)
                              (db/interval bounce-max-age)])]

      (and (< (or complaints 0) complaint-threshold)
           (< (or bounces 0) bounce-threshold)))))

(defn has-complaint-reports?
  ([conn email] (has-complaint-reports? conn email nil))
  ([conn email {:keys [threshold] :or {threshold 1}}]
   (let [reports (db/exec! conn (sql/select :global-complaint-report
                                            {:email email :type "complaint"}
                                            {:limit 10}))]
     (>= (count reports) threshold))))

(defn has-bounce-reports?
  ([conn email] (has-bounce-reports? conn email nil))
  ([conn email {:keys [threshold] :or {threshold 1}}]
   (let [reports (db/exec! conn (sql/select :global-complaint-report
                                            {:email email :type "bounce"}
                                            {:limit 10}))]
     (>= (count reports) threshold))))

(defn has-reports?
  ([conn email] (has-reports? conn email nil))
  ([conn email {:keys [threshold] :or {threshold 1}}]
   (let [reports (db/exec! conn (sql/select :global-complaint-report
                                            {:email email}
                                            {:limit 10}))]
     (>= (count reports) threshold))))
