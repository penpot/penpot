# Management Guide #

**TODO**


## Collections import ##

This is the way we can preload default collections of images and icons to the
running platform.

First of that, you need to have a configuration file (edn format) like
this:

```clojure
{:icons
 [{:name "Generic Icons 1"
   :path "./icons/my-icons-collection/"
   :regex #"^.*_48px\.svg$"}
  ]
 :images
 [{:name "Generic Images 1"
   :path "./images/my-images-collection/"
   :regex #"^.*\.(png|jpg|webp)$"}]}
```

You can found a real example in `sample_media/config.edn` (that also
has all the material design icon collections).

Then, you need to execute:

```bash
clojure -Adev -m uxbox.media-loader ../path/to/config.edn
```
