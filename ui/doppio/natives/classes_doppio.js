'use strict';
var Doppio = require('../doppiojvm');
var logging = Doppio.Debug.Logging;
var util = Doppio.VM.Util;
var classes_doppio_Debug = function () {
    function classes_doppio_Debug() {
    }
    classes_doppio_Debug['SetLogLevel(Lclasses/doppio/Debug$LogLevel;)V'] = function (thread, loglevel) {
        logging.log_level = loglevel['classes/doppio/Debug$LogLevel/level'];
    };
    classes_doppio_Debug['GetLogLevel()Lclasses/doppio/Debug$LogLevel;'] = function (thread) {
        var ll_cls = thread.getBsCl().getInitializedClass(thread, 'Lclasses/doppio/Debug$LogLevel;').getConstructor(thread);
        switch (logging.log_level) {
        case 10:
            return ll_cls['classes/doppio/Debug$LogLevel/VTRACE'];
        case 9:
            return ll_cls['classes/doppio/Debug$LogLevel/TRACE'];
        case 5:
            return ll_cls['classes/doppio/Debug$LogLevel/DEBUG'];
        default:
            return ll_cls['classes/doppio/Debug$LogLevel/ERROR'];
        }
    };
    return classes_doppio_Debug;
}();
var classes_doppio_JavaScript = function () {
    function classes_doppio_JavaScript() {
    }
    classes_doppio_JavaScript['eval(Ljava/lang/String;)Ljava/lang/String;'] = function (thread, to_eval) {
        try {
            var rv = eval(to_eval.toString());
            if (rv != null) {
                return util.initString(thread.getBsCl(), '' + rv);
            } else {
                return null;
            }
        } catch (e) {
            thread.throwNewException('Ljava/lang/Exception;', 'Error evaluating string: ' + e);
        }
    };
    return classes_doppio_JavaScript;
}();
registerNatives({
    'classes/doppio/Debug': classes_doppio_Debug,
    'classes/doppio/JavaScript': classes_doppio_JavaScript
});
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJzb3VyY2VzIjpbIi4uLy4uLy4uLy4uL3NyYy9uYXRpdmVzL2NsYXNzZXNfZG9wcGlvLnRzIl0sIm5hbWVzIjpbXSwibWFwcGluZ3MiOiI7QUFDQSxJQUFZLE1BQUEsR0FBTSxPQUFBLENBQU0sY0FBTixDQUFsQjtBQUdBLElBQU8sT0FBQSxHQUFVLE1BQUEsQ0FBTyxLQUFQLENBQWEsT0FBOUI7QUFDQSxJQUFPLElBQUEsR0FBTyxNQUFBLENBQU8sRUFBUCxDQUFVLElBQXhCO0FBR0EsSUFBQSxvQkFBQSxHQUFBLFlBQUE7QUFBQSxJQUFBLFNBQUEsb0JBQUEsR0FBQTtBQUFBLEtBQUE7QUFBQSxJQUVnQixvQkFBQSxDQUFBLCtDQUFBLElBQWQsVUFBOEQsTUFBOUQsRUFBaUYsUUFBakYsRUFBaUk7QUFBQSxRQUMvSCxPQUFBLENBQVEsU0FBUixHQUFvQixRQUFBLENBQVMscUNBQVQsQ0FBcEIsQ0FEK0g7QUFBQSxLQUFuSCxDQUZoQjtBQUFBLElBTWdCLG9CQUFBLENBQUEsOENBQUEsSUFBZCxVQUE2RCxNQUE3RCxFQUE4RTtBQUFBLFFBQzVFLElBQUksTUFBQSxHQUF1SCxNQUFBLENBQU8sT0FBUCxHQUFpQixtQkFBakIsQ0FBcUMsTUFBckMsRUFBNkMsaUNBQTdDLEVBQWlGLGNBQWpGLENBQWdHLE1BQWhHLENBQTNILENBRDRFO0FBQUEsUUFFNUUsUUFBUSxPQUFBLENBQVEsU0FBaEI7QUFBQSxRQUNFLEtBQUssRUFBTDtBQUFBLFlBQ0UsT0FBTyxNQUFBLENBQU8sc0NBQVAsQ0FBUCxDQUZKO0FBQUEsUUFHRSxLQUFLLENBQUw7QUFBQSxZQUNFLE9BQU8sTUFBQSxDQUFPLHFDQUFQLENBQVAsQ0FKSjtBQUFBLFFBS0UsS0FBSyxDQUFMO0FBQUEsWUFDRSxPQUFPLE1BQUEsQ0FBTyxxQ0FBUCxDQUFQLENBTko7QUFBQSxRQU9FO0FBQUEsWUFDRSxPQUFPLE1BQUEsQ0FBTyxxQ0FBUCxDQUFQLENBUko7QUFBQSxTQUY0RTtBQUFBLEtBQWhFLENBTmhCO0FBQUEsSUFvQkEsT0FBQSxvQkFBQSxDQXBCQTtBQUFBLENBQUEsRUFBQTtBQXNCQSxJQUFBLHlCQUFBLEdBQUEsWUFBQTtBQUFBLElBQUEsU0FBQSx5QkFBQSxHQUFBO0FBQUEsS0FBQTtBQUFBLElBRWdCLHlCQUFBLENBQUEsNENBQUEsSUFBZCxVQUEyRCxNQUEzRCxFQUE4RSxPQUE5RSxFQUFnSDtBQUFBLFFBQzlHLElBQUk7QUFBQSxZQUNGLElBQUksRUFBQSxHQUFLLElBQUEsQ0FBSyxPQUFBLENBQVEsUUFBUixFQUFMLENBQVQsQ0FERTtBQUFBLFlBR0YsSUFBSSxFQUFBLElBQU0sSUFBVixFQUFnQjtBQUFBLGdCQUNkLE9BQU8sSUFBQSxDQUFLLFVBQUwsQ0FBZ0IsTUFBQSxDQUFPLE9BQVAsRUFBaEIsRUFBa0MsS0FBSyxFQUF2QyxDQUFQLENBRGM7QUFBQSxhQUFoQixNQUVPO0FBQUEsZ0JBQ0wsT0FBTyxJQUFQLENBREs7QUFBQSxhQUxMO0FBQUEsU0FBSixDQVFFLE9BQU8sQ0FBUCxFQUFVO0FBQUEsWUFDVixNQUFBLENBQU8saUJBQVAsQ0FBeUIsdUJBQXpCLEVBQWtELDhCQUE0QixDQUE5RSxFQURVO0FBQUEsU0FUa0c7QUFBQSxLQUFsRyxDQUZoQjtBQUFBLElBZ0JBLE9BQUEseUJBQUEsQ0FoQkE7QUFBQSxDQUFBLEVBQUE7QUFrQkEsZUFBQSxDQUFnQjtBQUFBLElBQ2Qsd0JBQXdCLG9CQURWO0FBQUEsSUFFZCw2QkFBNkIseUJBRmY7QUFBQSxDQUFoQiIsInNvdXJjZXNDb250ZW50IjpbImltcG9ydCBKVk1UeXBlcyA9IHJlcXVpcmUoJy4uLy4uL2luY2x1ZGVzL0pWTVR5cGVzJyk7XG5pbXBvcnQgKiBhcyBEb3BwaW8gZnJvbSAnLi4vZG9wcGlvanZtJztcbmltcG9ydCBKVk1UaHJlYWQgPSBEb3BwaW8uVk0uVGhyZWFkaW5nLkpWTVRocmVhZDtcbmltcG9ydCBSZWZlcmVuY2VDbGFzc0RhdGEgPSBEb3BwaW8uVk0uQ2xhc3NGaWxlLlJlZmVyZW5jZUNsYXNzRGF0YTtcbmltcG9ydCBsb2dnaW5nID0gRG9wcGlvLkRlYnVnLkxvZ2dpbmc7XG5pbXBvcnQgdXRpbCA9IERvcHBpby5WTS5VdGlsO1xuZGVjbGFyZSB2YXIgcmVnaXN0ZXJOYXRpdmVzOiAoZGVmczogYW55KSA9PiB2b2lkO1xuXG5jbGFzcyBjbGFzc2VzX2RvcHBpb19EZWJ1ZyB7XG5cbiAgcHVibGljIHN0YXRpYyAnU2V0TG9nTGV2ZWwoTGNsYXNzZXMvZG9wcGlvL0RlYnVnJExvZ0xldmVsOylWJyh0aHJlYWQ6IEpWTVRocmVhZCwgbG9nbGV2ZWw6IEpWTVR5cGVzLmNsYXNzZXNfZG9wcGlvX0RlYnVnJExvZ0xldmVsKTogdm9pZCB7XG4gICAgbG9nZ2luZy5sb2dfbGV2ZWwgPSBsb2dsZXZlbFsnY2xhc3Nlcy9kb3BwaW8vRGVidWckTG9nTGV2ZWwvbGV2ZWwnXTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ0dldExvZ0xldmVsKClMY2xhc3Nlcy9kb3BwaW8vRGVidWckTG9nTGV2ZWw7Jyh0aHJlYWQ6IEpWTVRocmVhZCk6IEpWTVR5cGVzLmNsYXNzZXNfZG9wcGlvX0RlYnVnJExvZ0xldmVsIHtcbiAgICB2YXIgbGxfY2xzID0gPHR5cGVvZiBKVk1UeXBlcy5jbGFzc2VzX2RvcHBpb19EZWJ1ZyRMb2dMZXZlbD4gKDxSZWZlcmVuY2VDbGFzc0RhdGE8SlZNVHlwZXMuY2xhc3Nlc19kb3BwaW9fRGVidWckTG9nTGV2ZWw+PiB0aHJlYWQuZ2V0QnNDbCgpLmdldEluaXRpYWxpemVkQ2xhc3ModGhyZWFkLCAnTGNsYXNzZXMvZG9wcGlvL0RlYnVnJExvZ0xldmVsOycpKS5nZXRDb25zdHJ1Y3Rvcih0aHJlYWQpO1xuICAgIHN3aXRjaCAobG9nZ2luZy5sb2dfbGV2ZWwpIHtcbiAgICAgIGNhc2UgMTA6XG4gICAgICAgIHJldHVybiBsbF9jbHNbJ2NsYXNzZXMvZG9wcGlvL0RlYnVnJExvZ0xldmVsL1ZUUkFDRSddO1xuICAgICAgY2FzZSA5OlxuICAgICAgICByZXR1cm4gbGxfY2xzWydjbGFzc2VzL2RvcHBpby9EZWJ1ZyRMb2dMZXZlbC9UUkFDRSddO1xuICAgICAgY2FzZSA1OlxuICAgICAgICByZXR1cm4gbGxfY2xzWydjbGFzc2VzL2RvcHBpby9EZWJ1ZyRMb2dMZXZlbC9ERUJVRyddO1xuICAgICAgZGVmYXVsdDpcbiAgICAgICAgcmV0dXJuIGxsX2Nsc1snY2xhc3Nlcy9kb3BwaW8vRGVidWckTG9nTGV2ZWwvRVJST1InXTtcbiAgICB9XG4gIH1cblxufVxuXG5jbGFzcyBjbGFzc2VzX2RvcHBpb19KYXZhU2NyaXB0IHtcblxuICBwdWJsaWMgc3RhdGljICdldmFsKExqYXZhL2xhbmcvU3RyaW5nOylMamF2YS9sYW5nL1N0cmluZzsnKHRocmVhZDogSlZNVGhyZWFkLCB0b19ldmFsOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nKTogSlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZyB7XG4gICAgdHJ5IHtcbiAgICAgIHZhciBydiA9IGV2YWwodG9fZXZhbC50b1N0cmluZygpKTtcbiAgICAgIC8vIENvZXJjZSB0byBzdHJpbmcsIGlmIHBvc3NpYmxlLlxuICAgICAgaWYgKHJ2ICE9IG51bGwpIHtcbiAgICAgICAgcmV0dXJuIHV0aWwuaW5pdFN0cmluZyh0aHJlYWQuZ2V0QnNDbCgpLCBcIlwiICsgcnYpO1xuICAgICAgfSBlbHNlIHtcbiAgICAgICAgcmV0dXJuIG51bGw7XG4gICAgICB9XG4gICAgfSBjYXRjaCAoZSkge1xuICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL0V4Y2VwdGlvbjsnLCBgRXJyb3IgZXZhbHVhdGluZyBzdHJpbmc6ICR7ZX1gKTtcbiAgICB9XG4gIH1cblxufVxuXG5yZWdpc3Rlck5hdGl2ZXMoe1xuICAnY2xhc3Nlcy9kb3BwaW8vRGVidWcnOiBjbGFzc2VzX2RvcHBpb19EZWJ1ZyxcbiAgJ2NsYXNzZXMvZG9wcGlvL0phdmFTY3JpcHQnOiBjbGFzc2VzX2RvcHBpb19KYXZhU2NyaXB0XG59KTtcbiJdfQ==