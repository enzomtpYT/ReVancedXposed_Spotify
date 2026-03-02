package io.github.chsbuffer.revancedxposed.spotify.misc

import app.revanced.extension.shared.Logger
import app.revanced.extension.spotify.misc.UnlockPremiumPatch
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.callMethod
import io.github.chsbuffer.revancedxposed.findField
import io.github.chsbuffer.revancedxposed.findFirstFieldByExactType
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Constructor
import java.lang.reflect.Field

@Suppress("UNCHECKED_CAST")
fun SpotifyHook.UnlockPremium() {
    // Override the attributes map in the getter method's return value.
    // Creates a defensive copy with cloned attribute objects.
    // Importantly, uses Stack Trace Filtering (Client/Server Isolation):
    // If the caller is a network/sync process, it receives the genuine Free state.
    // If the caller is the local app (UI/Player), it receives the spoofed Premium state.
    runCatching {
        ::productStateProtoFingerprint.hookMethod {
            after { param ->
                val result = param.result as? Map<String, *> ?: return@after
                
                if (UnlockPremiumPatch.isNetworkSyncCall()) {
                    Logger.printDebug { "Stack Trace Filter: Detected network sync. Providing original FREE state to server." }
                    return@after // Provide the original state to the server to prevent bans
                }
                
                // Provide the spoofed PREMIUM state to the local application
                param.result = UnlockPremiumPatch.createOverriddenAttributesMap(result)
            }
        }
    }.onFailure { Logger.printDebug { "productStateProtoFingerprint hook failed: ${it.message}" } }

    // Add the query parameter trackRows to show popular tracks in the artist page.
    runCatching {
        ::buildQueryParametersFingerprint.hookMethod {
            after { param ->
                val result = param.result
                val FIELD = "checkDeviceCapability"
                if (result.toString().contains("${FIELD}=")) {
                    param.result = XposedBridge.invokeOriginalMethod(
                        param.method, param.thisObject, arrayOf(param.args[0], true)
                    )
                }
            }
        }
    }.onFailure { Logger.printDebug { "buildQueryParametersFingerprint hook failed: ${it.message}" } }

    // Enable choosing a specific song/artist via Google Assistant.
    runCatching {
        ::contextFromJsonFingerprint.hookMethod {
            fun removeStationString(field: Field, obj: Any) {
                field.set(obj, UnlockPremiumPatch.removeStationString(field.get(obj) as String))
            }

            after { param ->
                val thiz = param.result
                val clazz = param.result.javaClass
                removeStationString(clazz.findField("uri"), thiz)
                removeStationString(clazz.findField("url"), thiz)
            }
        }
    }.onFailure { Logger.printDebug { "contextFromJsonFingerprint hook failed: ${it.message}" } }

    // Disable forced shuffle when asking for an album/playlist via Google Assistant.
    // Wrapped in runCatching so a class name change doesn't crash the entire hook chain.
    runCatching {
        XposedHelpers.findAndHookMethod(
            "com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides\$Builder",
            classLoader,
            "build",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.thisObject.callMethod("shufflingContext", false)
                }
            })
    }.onFailure { Logger.printDebug { "PlayerOptionOverrides hook failed: ${it.message}" } }

    // Hook the method which adds context menu items and return before adding if the item is a Premium ad.
    val contextMenuViewModelClazz = ::contextMenuViewModelClass.clazz
    XposedBridge.hookAllConstructors(
        contextMenuViewModelClazz, object : XC_MethodHook() {
            val isPremiumUpsell = ::isPremiumUpsellField.field

            override fun beforeHookedMethod(param: MethodHookParam) {
                val parameterTypes = (param.method as Constructor<*>).parameterTypes
                Logger.printDebug { "ContextMenuViewModel(${parameterTypes.joinToString(",") { it.name }})" }
                for (i in 0 until param.args.size) {
                    if (parameterTypes[i].name != "java.util.List") continue
                    val original = param.args[i] as? List<*> ?: continue
                    Logger.printDebug { "List value type: ${original.firstOrNull()?.javaClass}" }
                    val filtered = original.filter {
                        it!!.callMethod("getViewModel").let { isPremiumUpsell.get(it) } != true
                    }
                    param.args[i] = filtered
                    Logger.printDebug { "Filtered ${original.size - filtered.size} context menu items." }
                }
            }
        })

    // Remove ads sections from home.
    // Returns a filtered copy instead of mutating the original protobuf list,
    // preventing detection through protobuf integrity checks.
    runCatching {
        ::homeStructureGetSectionsFingerprint.hookMethod {
            after { param ->
                param.result = UnlockPremiumPatch.filterHomeSections(param.result as List<*>)
            }
        }
    }.onFailure { Logger.printDebug { "homeStructureGetSectionsFingerprint hook failed: ${it.message}" } }
    // Remove ads sections from browser.
    runCatching {
        ::browseStructureGetSectionsFingerprint.hookMethod {
            after { param ->
                param.result = UnlockPremiumPatch.filterBrowseSections(param.result as List<*>)
            }
        }
    }.onFailure { Logger.printDebug { "browseStructureGetSectionsFingerprint hook failed: ${it.message}" } }

    // Remove pendragon (pop up ads) requests and return the errors instead.
    // No network request is made — the error value is extracted from the app's own
    // onErrorReturn handler, so this mimics a natural request failure.
    val replaceFetchRequestSingleWithError = object : XC_MethodHook() {
        val justMethod =
            DexMethod("Lio/reactivex/rxjava3/core/Single;->just(Ljava/lang/Object;)Lio/reactivex/rxjava3/core/Single;").toMethod()

        val onErrorField =
            DexField("Lio/reactivex/rxjava3/internal/operators/single/SingleOnErrorReturn;->b:Lio/reactivex/rxjava3/functions/Function;").toField()

        override fun afterHookedMethod(param: MethodHookParam) {
            if (!param.result.javaClass.name.endsWith("SingleOnErrorReturn")) return
            
            // Extract the fallback Function from the SingleOnErrorReturn object
            val onErrorFunction = onErrorField.get(param.result)
            
            // Invoke the fallback Function with a simulated exception
            // This generates the mock FetchMessageResponse exactly as Spotify intended
            val mockException = Exception("Blocked ad request")
            val fallbackResponse = onErrorFunction.javaClass.methods
                .first { it.name == "apply" && it.parameterTypes.size == 1 }
                .invoke(onErrorFunction, mockException)
                
            // Wrap the mock response in Single.just()
            val justErrorSingle = justMethod.invoke(null, fallbackResponse)
            
            param.result = justErrorSingle
        }
    }

    runCatching {
        ::pendragonJsonFetchMessageRequestFingerprint.hookMethod(replaceFetchRequestSingleWithError)
    }.onFailure { Logger.printDebug { "pendragonJsonFetchMessageRequestFingerprint hook failed: ${it.message}" } }
    
    runCatching {
        ::pendragonJsonFetchMessageListRequestFingerprint.hookMethod(replaceFetchRequestSingleWithError)
    }.onFailure { Logger.printDebug { "pendragonJsonFetchMessageListRequestFingerprint hook failed: ${it.message}" } }
}