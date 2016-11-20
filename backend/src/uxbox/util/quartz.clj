;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.quartz
  "A lightweight abstraction layer for quartz job scheduling library."
  (:import java.util.Properties
           org.quartz.Scheduler
           org.quartz.SchedulerException
           org.quartz.impl.StdSchedulerFactory
           org.quartz.Job
           org.quartz.JobBuilder
           org.quartz.JobDataMap
           org.quartz.JobExecutionContext
           org.quartz.TriggerBuilder
           org.quartz.CronScheduleBuilder
           org.quartz.SimpleScheduleBuilder
           org.quartz.PersistJobDataAfterExecution
           org.quartz.DisallowConcurrentExecution))

;; --- Implementation

(defn- map->props
  [data]
  (let [p (Properties.)]
    (run! (fn [[k v]] (.setProperty p (name k) (str v))) (seq data))
    p))

(deftype JobImpl []
  Job
  (execute [_ context]
    (let [^JobDataMap data (.. context getJobDetail getJobDataMap)
          args (.get data "arguments")
          state (.get data "state")
          callable (.get data "callable")]
      (if state
        (apply callable state args)
        (apply callable args)))))

(defn- resolve-var
  [sym]
  (let [ns (symbol (namespace sym))
        func (symbol (name sym))]
    (require ns)
    (resolve func)))

(defn- build-trigger
  [opts]
  (let [repeat? (::repeat? opts true)
        interval (::interval opts 1000)
        cron (::cron opts)
        group (::group opts "uxbox")
        schdl (if cron
                (CronScheduleBuilder/cronSchedule cron)
                (let [schdl (SimpleScheduleBuilder/simpleSchedule)
                      schdl (if (number? repeat?)
                              (.withRepeatCount schdl repeat?)
                              (.repeatForever schdl))]
                  (.withIntervalInMilliseconds schdl interval)))
        name (str (:name opts) "-trigger")
        bldr (doto (TriggerBuilder/newTrigger)
               (.startNow)
               (.withIdentity name group)
               (.withSchedule schdl))]
    (.build bldr)))

(defn- build-job-detail
  [fvar args]
  (let [opts (meta fvar)
        state (::state opts)
        group (::group opts "uxbox")
        name  (str (:name opts))
        data  {"callable" @fvar
               "arguments" (into [] args)
               "state" (if state (atom state) nil)}
        bldr (doto (JobBuilder/newJob JobImpl)
               (.storeDurably false)
               (.usingJobData (JobDataMap. data))
               (.withIdentity name group))]
    (.build bldr)))

(defn- make-scheduler-props
  [{:keys [name daemon? threads thread-priority]
    :or {name "uxbox-scheduler"
         daemon? true
         threads 1
         thread-priority Thread/MIN_PRIORITY}}]
  (map->props
   {"org.quartz.threadPool.threadCount" threads
    "org.quartz.threadPool.threadPriority" thread-priority
    "org.quartz.threadPool.makeThreadsDaemons" (if daemon? "true" "false")
    "org.quartz.scheduler.instanceName" name
    "org.quartz.scheduler.makeSchedulerThreadDaemon" (if daemon? "true" "false")}))

;; --- Public Api

(defn scheduler
  "Create a new scheduler instance."
  ([] (scheduler nil))
  ([opts]
   (let [props (make-scheduler-props opts)
         factory (StdSchedulerFactory. props)]
     (.getScheduler factory))))

(declare schedule!)

(defn start!
  ([schd]
   (start! schd nil))
  ([schd {:keys [delay search-on]}]
   ;; Start the scheduler
   (if (number? delay)
     (.startDelayed schd (int delay))
     (.start schd))

   (when (coll? search-on)
     (run! (fn [ns]
             (require ns)
             (doseq [v (vals (ns-publics ns))]
               (when (::job (meta v))
                 (schedule! schd v))))
           search-on))
   schd))

(defn stop!
  [scheduler]
  (.shutdown ^Scheduler scheduler true))

;; TODO: add proper handling of `:delay` option that should allow
;; execute a task firstly delayed until some milliseconds or at certain time.

(defn schedule!
  [schd f & args]
  (let [vf (if (symbol? f) (resolve-var f) f)
        job (build-job-detail vf args)
        trigger (build-trigger (meta vf))]
    (.scheduleJob ^Scheduler schd job trigger)))
