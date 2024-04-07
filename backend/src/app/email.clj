;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.email
  "Main api for send emails."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.pprint :as pp]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.email.invite-to-team :as-alias email.invite-to-team]
   [app.metrics :as mtx]
   [app.util.template :as tmpl]
   [app.worker :as wrk]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
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

(defn- create-smtp-session
  ^Session
  [cfg]
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

(s/def ::priority #{:high :low})
(s/def ::to (s/or :single ::us/email
                  :multi (s/coll-of ::us/email)))
(s/def ::from ::us/email)
(s/def ::reply-to ::us/email)
(s/def ::lang string?)
(s/def ::extra-data ::us/string)

(s/def ::context
  (s/keys :req-un [::to]
          :opt-un [::reply-to ::from ::lang ::priority ::extra-data]))

(defn template-factory
  ([id] (template-factory id {}))
  ([id extra-context]
   (s/assert keyword? id)
   (fn [context]
     (us/verify ::context context)
     (when-let [spec (s/get-spec id)]
       (s/assert spec context))

     (let [context (merge (if (fn? extra-context)
                            (extra-context)
                            extra-context)
                          context)
           email   (build-email-template id context)]
       (when-not email
         (ex/raise :type :internal
                   :code :email-template-does-not-exists
                   :hint "seems like the template is wrong or does not exists."
                   :context {:id id}))
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
  (us/verify some? conn)
  (let [email (if factory
                (factory context)
                (dissoc context ::conn))]
    (wrk/submit! (merge
                  {::wrk/task :sendmail
                   ::wrk/delay 0
                   ::wrk/max-retries 4
                   ::wrk/priority 200
                   ::wrk/conn conn}
                  email))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SENDMAIL FN / TASK HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::username ::cf/smtp-username)
(s/def ::password ::cf/smtp-password)
(s/def ::tls ::cf/smtp-tls)
(s/def ::ssl ::cf/smtp-ssl)
(s/def ::host ::cf/smtp-host)
(s/def ::port ::cf/smtp-port)
(s/def ::default-reply-to ::cf/smtp-default-reply-to)
(s/def ::default-from ::cf/smtp-default-from)

(s/def ::smtp-config
  (s/keys :opt [::username
                ::password
                ::tls
                ::ssl
                ::host
                ::port
                ::default-from
                ::default-reply-to]))

(declare send-to-logger!)

(s/def ::sendmail fn?)

(defmethod ig/pre-init-spec ::sendmail [_]
  (s/spec ::smtp-config))

(defmethod ig/init-key ::sendmail
  [_ cfg]
  (fn [params]
    (when (contains? cf/flags :smtp)
      (let [session (create-smtp-session cfg)]
        (with-open [transport (.getTransport session (if (::ssl cfg) "smtps" "smtp"))]
          (.connect ^Transport transport
                    ^String (::username cfg)
                    ^String (::password cfg))

          (let [^MimeMessage message (create-smtp-message cfg session params)]
            (l/dbg :hint "sendmail"
                   :id (:id params)
                   :to (:to params)
                   :subject (str/trim (:subject params))
                   :body (str/join "," (map :type (:body params))))

            (.sendMessage ^Transport transport
                          ^MimeMessage message
                          (.getAllRecipients message))))))

    (when (or (contains? cf/flags :log-emails)
              (not (contains? cf/flags :smtp)))
      (send-to-logger! cfg params))))

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req [::sendmail ::mtx/metrics]))

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

(s/def ::subject ::us/string)
(s/def ::content ::us/string)

(s/def ::feedback
  (s/keys :req-un [::subject ::content]))

(def feedback
  "A profile feedback email."
  (template-factory ::feedback))

(s/def ::name ::us/string)
(s/def ::register
  (s/keys :req-un [::name]))

(def register
  "A new profile registration welcome email."
  (template-factory ::register))

(s/def ::token ::us/string)
(s/def ::password-recovery
  (s/keys :req-un [::name ::token]))

(def password-recovery
  "A password recovery notification email."
  (template-factory ::password-recovery))

(s/def ::pending-email ::us/email)
(s/def ::change-email
  (s/keys :req-un [::name ::pending-email ::token]))

(def change-email
  "Password change confirmation email"
  (template-factory ::change-email))

(s/def ::email.invite-to-team/invited-by ::us/string)
(s/def ::email.invite-to-team/team ::us/string)
(s/def ::email.invite-to-team/token ::us/string)

(s/def ::invite-to-team
  (s/keys :req-un [::email.invite-to-team/invited-by
                   ::email.invite-to-team/token
                   ::email.invite-to-team/team]))

(def invite-to-team
  "Teams member invitation email."
  (template-factory ::invite-to-team))

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
