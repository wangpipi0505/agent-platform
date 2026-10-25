# admin-common/src/main/java/cn/agentplatform/common/authority/GatewayIngressBoundary.java

1: package cn.agentplatform.common.authority;
2: 
3: import cn.agentplatform.common.*;
4: 
5: import jakarta.servlet.*;
6: import jakarta.servlet.http.*;
7: 
8: import org.springframework.aop.support.AopUtils;
9: import org.springframework.boot.web.servlet.FilterRegistrationBean;
10: import org.springframework.security.core.context.SecurityContextHolder;
11: import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
12: import org.springframework.security.web.*;
13: import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
14: import org.springframework.security.web.util.matcher.AnyRequestMatcher;
15: import org.springframework.web.filter.GenericFilterBean;
16: import org.springframework.web.method.HandlerMethod;
17: import org.springframework.web.servlet.AsyncHandlerInterceptor;
18: import org.springframework.web.servlet.handler.MappedInterceptor;
19: import org.springframework.web.context.request.async.*;
20: 
21: import java.io.IOException;
22: import java.lang.reflect.Method;
23: import java.time.*;
24: import java.util.*;
25: 
26: /**
27:  * A private request capability, owned by the installed filter and consumed once by the actual MVC
28:  * handler.
29:  */
30: public final class GatewayIngressBoundary implements AsyncHandlerInterceptor {
31:     private static final String KEY = GatewayIngressBoundary.class.getName() + ".proof";
32:     private static final String CONTROLLER = "cn.agentplatform.gateway.api.GatewayController";
33:     private final Object controller;
34:     private final Method method;
35:     private static final String SESSION_CONTROLLER="cn.agentplatform.gateway.api.PortalSessionController";
36:     private enum Purpose { RUN, SESSION, STATUS, EVENTS }
37:     private final boolean statusInstalled,eventsInstalled;
38:     private final Object sessionController;
39:     private final Method sessionMethod;
40:     private final TrustedJwtDecoder decoder;
41:     private final Clock clock;
42:     private final Filter filter = new IngressFilter();
43: 
44:     public GatewayIngressBoundary(Object controller, TrustedJwtDecoder decoder, Clock clock) {
45:         this(controller,null,decoder,clock);
46:     }
47: 
48:     /** A second fixed purpose, never an arbitrary caller-provided handler allow-list. */
49:     public GatewayIngressBoundary(Object controller,Object sessionController,TrustedJwtDecoder decoder,Clock clock) {
50:         this(controller,sessionController,false,decoder,clock);
51:     }
52: 
53:     public GatewayIngressBoundary(Object controller,Object sessionController,boolean statusInstalled,TrustedJwtDecoder decoder,Clock clock) {
54:         this(controller,sessionController,statusInstalled,false,decoder,clock);
55:     }
56: 
57:     public GatewayIngressBoundary(Object controller,Object sessionController,boolean statusInstalled,boolean eventsInstalled,TrustedJwtDecoder decoder,Clock clock) {
58:         this.controller = Objects.requireNonNull(controller);
59:         this.sessionController=sessionController;
60:         this.statusInstalled=statusInstalled;
61:         this.eventsInstalled=eventsInstalled;
62:         this.decoder = Objects.requireNonNull(decoder);
63:         this.clock = Objects.requireNonNull(clock);
64:         try {
65:             Class<?> type = AopUtils.getTargetClass(controller);
66:             if (!CONTROLLER.equals(type.getName())
67:                     || type != Class.forName(CONTROLLER, false, type.getClassLoader()))
68:                 throw new IllegalArgumentException("GATEWAY_HANDLER_REQUIRED");
69:             method =
70:                     type.getDeclaredMethod(
71:                             "proxy", HttpServletRequest.class, HttpServletResponse.class);
72:             if (method.getReturnType() != void.class)
73:                 throw new IllegalArgumentException("GATEWAY_HANDLER_REQUIRED");
74:             if(sessionController==null)sessionMethod=null;
75:             else {
76:                 Class<?> sessionType=AopUtils.getTargetClass(sessionController);
77:                 if(!SESSION_CONTROLLER.equals(sessionType.getName())||sessionType!=Class.forName(SESSION_CONTROLLER,false,sessionType.getClassLoader()))throw new IllegalArgumentException("GATEWAY_SESSION_HANDLER_REQUIRED");
78:                 sessionMethod=sessionType.getDeclaredMethod("session",HttpServletRequest.class,HttpServletResponse.class);
79:                 if(sessionMethod.getReturnType()!=void.class)throw new IllegalArgumentException("GATEWAY_SESSION_HANDLER_REQUIRED");
80:             }
81:         } catch (ReflectiveOperationException e) {
82:             throw new IllegalArgumentException("GATEWAY_HANDLER_REQUIRED", e);
83:         }
84:     }
85: 
86:     public Filter filter() {
87:         return filter;
88:     }
89: 
90:     public MappedInterceptor mappedInterceptor() {
91:         return new MappedInterceptor(null, null, this);
92:     }
93: 
94:     public boolean owns(HandlerMethod h) {
95:         return owns(h,controller,method);
96:     }
97:     public boolean sessionInstalled(){return sessionController!=null;}
98:     public boolean ownsSession(HandlerMethod h){return sessionInstalled()&&owns(h,sessionController,sessionMethod);}
99:     private static boolean owns(HandlerMethod h,Object controller,Method method){
100:         var actual = h.createWithResolvedBean();
101:         return actual.getBean() == controller
102:                 && AopUtils.getTargetClass(actual.getBean()) == method.getDeclaringClass()
103:                 && AopUtils.getMostSpecificMethod(
104:                                 actual.getMethod(), AopUtils.getTargetClass(actual.getBean()))
105:                         .equals(method);
106:     }
107: 
108:     private Purpose route(HttpServletRequest r) {
109:         if(eventsInstalled&&r.getDispatcherType()==DispatcherType.REQUEST&&"GET".equals(r.getMethod())
110:                 &&r.getRequestURI().matches("/admin/runs/r_[0-9a-f]{32}/events")&&r.getContextPath().isEmpty())return Purpose.EVENTS;
111:         if(statusInstalled&&r.getDispatcherType()==DispatcherType.REQUEST&&"GET".equals(r.getMethod())
112:                 &&r.getRequestURI().matches("/admin/runs/r_[0-9a-f]{32}")&&r.getQueryString()==null&&r.getContextPath().isEmpty())return Purpose.STATUS;
113:         if(sessionInstalled()&&r.getDispatcherType()==DispatcherType.REQUEST&&"GET".equals(r.getMethod())
114:                 &&"/auth/session".equals(r.getRequestURI())&&r.getQueryString()==null&&r.getContextPath().isEmpty())return Purpose.SESSION;
115:         if (r.getDispatcherType() != DispatcherType.REQUEST
116:                 || !"POST".equals(r.getMethod())
117:                 || r.getQueryString() != null
118:                 || !r.getContextPath().isEmpty()
119:                 || !r.getRequestURI().matches("/admin/apps/[A-Za-z][A-Za-z0-9_-]{0,63}/runs"))
120:             throw denied();
121:         return Purpose.RUN;
122:     }
123: 
124:     private static final class Proof {
125:         final GatewayIngressBoundary owner;
126:         final HttpServletRequest anchor;
127:         final String path, source, query;
128:         final long cursor;
129:         HttpServletRequest eventsRequest;
130:         HttpServletResponse eventsResponse;
131:         HttpServletResponse eventsHandlerResponse;
132:         WebAsyncManager eventsManager;
133:         AsyncWebRequest eventsAsyncRequest;
134:         final SubjectKey subject;
135:         final Instant expires;
136:         final Purpose purpose;
137:         final Thread thread=Thread.currentThread();
138:         final long startedNanos=System.nanoTime(),sourceBudget;
139:         boolean ready, claimed, invalid;
140: 
141:         Proof(
142:                 GatewayIngressBoundary owner,
143:                 HttpServletRequest anchor,
144:                 String source,
145:                 SubjectKey subject,
146:                 Instant expires,long cursor) {
147:             this.owner = owner;
148:             this.anchor = anchor;
149:             this.source = source;
150:             this.subject = subject;
151:             this.expires = expires;
152:             sourceBudget=remaining(owner.clock.instant(),expires);
153:             path = anchor.getRequestURI();
154:             query=anchor.getQueryString();this.cursor=cursor;
155:             purpose=owner.route(anchor);
156:         }
157: 
158:         void check(HttpServletRequest request) {
159:             if(owner.route(request)!=purpose)throw denied();
160:             if(!expires.isAfter(owner.clock.instant())&&(purpose==Purpose.SESSION||purpose==Purpose.STATUS||purpose==Purpose.EVENTS))throw new PlatformException(401,"PORTAL_TOKEN_INVALID","门户身份已失效");
161:             if(purpose==Purpose.EVENTS&&(Thread.currentThread()!=thread||!Objects.equals(query,request.getQueryString())))throw denied();
162:             if (invalid
163:                     || claimed
164:                     || !path.equals(request.getRequestURI())
165:                     || !expires.isAfter(owner.clock.instant())
166:                     || !hasAnchor(request, anchor)) throw denied();
167:         }
168:     }
169: 
170:     /**
171:      * Only the owned filter can create this capture; no Actor/VA or caller-supplied JWT can mint
172:      * it.
173:      */
174:     public static final class PortalRequest {
175:         private final String source, code;
176:         private final SubjectKey subject;
177: 
178:         private PortalRequest(Proof p) {
179:             source = p.source;
180:             subject = p.subject;
181:             code = p.path.substring(12, p.path.length() - 5);
182:         }
183: 
184:         public String sourceToken() {
185:             return source;
186:         }
187: 
188:         public String appCode() {
189:             return code;
190:         }
191: 
192:         public SubjectKey subject() {
193:             return subject;
194:         }
195: 
196:         @Override
197:         public String toString() {
198:             return "PortalRequest[private-capture]";
199:         }
200:     }
201: 
202:     private static boolean hasAnchor(ServletRequest request, ServletRequest anchor) {
203:         var seen = Collections.newSetFromMap(new IdentityHashMap<ServletRequest, Boolean>());
204:         for (int i = 0; i < 32 && request != null && seen.add(request); i++) {
205:             if (request == anchor) return true;
206:             if (!(request instanceof ServletRequestWrapper wrapper)) return false;
207:             request = wrapper.getRequest();
208:         }
209:         return false;
210:     }
211: 
212:     private static Proof proof(HttpServletRequest r) {
213:         if (!(r.getAttribute(KEY) instanceof Proof p)) throw denied();
214:         return p;
215:     }
216: 
217:     public static PortalRequest claim(HttpServletRequest r) {
218:         var p = proof(r);
219:         synchronized (p) {
220:             p.check(r);
221:             if (!p.ready||p.purpose!=Purpose.RUN) throw denied();
222:             p.claimed = true;
223:             return new PortalRequest(p);
224:         }
225:     }
226:     public static final class PortalSessionRequest {
227:         private final String source;
228:         private PortalSessionRequest(Proof p){source=p.source;}
229:         public String sourceToken(){return source;}
230:         @Override public String toString(){return "PortalSessionRequest[private-capture]";}
231:     }
232:     public static PortalSessionRequest claimSession(HttpServletRequest r){
233:         var p=proof(r);synchronized(p){p.check(r);if(!p.ready||p.purpose!=Purpose.SESSION)throw denied();p.claimed=true;return new PortalSessionRequest(p);}
234:     }
235: 
236:     /** This capability belongs to one synchronous captured request, including after stream acquisition. */
237:     public static final class PortalStatusRequest {
238:         private final Proof capture;
239:         private PortalStatusRequest(Proof p){capture=p;}
240:         public String sourceToken(){return capture.source;}
241:         public SubjectKey subject(){return capture.subject;}
242:         public String publicRunId(){return capture.path.substring("/admin/runs/".length());}
243:         public void requireLive(HttpServletRequest request){
244:             synchronized(capture){
245:                 var p=capture;long elapsed=System.nanoTime()-p.startedNanos;
246:                 if(!p.expires.isAfter(p.owner.clock.instant())||elapsed<0||elapsed>=p.sourceBudget)
247:                     throw new PlatformException(401,"PORTAL_TOKEN_INVALID","门户身份已失效");
248:                 if(p.purpose!=Purpose.STATUS||!p.claimed||!p.ready||p.invalid
249:                         ||Thread.currentThread()!=p.thread||request.getAttribute(KEY)!=p
250:                         ||!hasAnchor(request,p.anchor)||!p.path.equals(request.getRequestURI())
251:                         ||p.owner.route(request)!=Purpose.STATUS)throw denied();
252:             }
253:         }
254:         @Override public String toString(){return "PortalStatusRequest[private-capture]";}
255:     }
256:     private static long remaining(Instant start,Instant end){try{return Math.max(0,Duration.between(start,end).toNanos());}catch(ArithmeticException e){return Long.MAX_VALUE;}}
257:     public static PortalStatusRequest claimStatus(HttpServletRequest request){
258:         var p=proof(request);synchronized(p){p.check(request);if(!p.ready||p.purpose!=Purpose.STATUS)throw denied();p.claimed=true;var result=new PortalStatusRequest(p);result.requireLive(request);return result;}
259:     }
260: 
261:     /** One actual MVC argument pair, consumed on its original thread before async bootstrap. */
262:     public static final class PortalEventsRequest {
263:         private final Proof capture;
264:         private PortalEventsRequest(Proof capture){this.capture=capture;}
265:         public String sourceToken(){return capture.source;}
266:         public SubjectKey subject(){return capture.subject;}
267:         public String publicRunId(){return capture.path.substring("/admin/runs/".length(),capture.path.length()-"/events".length());}
268:         public long cursor(){return capture.cursor;}
269:         public void requireLive(HttpServletRequest request,HttpServletResponse response){
270:             synchronized(capture){
271:                 var p=capture;long elapsed=System.nanoTime()-p.startedNanos;
272:                 if(!p.expires.isAfter(p.owner.clock.instant())||elapsed<0||elapsed>=p.sourceBudget)
273:                     throw new PlatformException(401,"PORTAL_TOKEN_INVALID","门户身份已失效");
274:                 if(p.purpose!=Purpose.EVENTS||!p.claimed||!p.ready||p.invalid
275:                         ||Thread.currentThread()!=p.thread||request!=p.eventsRequest||response!=p.eventsHandlerResponse
276:                         ||request.getAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE)!=p.eventsManager
277:                         ||p.eventsManager==null||p.eventsManager.getAsyncWebRequest()!=p.eventsAsyncRequest
278:                         ||request.getAttribute(KEY)!=p||!hasAnchor(request,p.anchor)
279:                         ||!p.path.equals(request.getRequestURI())||!Objects.equals(p.query,request.getQueryString())
280:                         ||p.owner.route(request)!=Purpose.EVENTS)throw denied();
281:             }
282:         }
283:         @Override public String toString(){return "PortalEventsRequest[private-capture]";}
284:     }
285:     public static PortalEventsRequest claimEvents(HttpServletRequest request,HttpServletResponse response){
286:         var p=proof(request);synchronized(p){
287:             p.check(request);
288:             if(!p.ready||p.purpose!=Purpose.EVENTS||request!=p.eventsRequest)throw denied();
289:             bindEventsResponse(p,request,response);
290:             p.claimed=true;var result=new PortalEventsRequest(p);result.requireLive(request,response);return result;
291:         }
292:     }
293:     private static void bindEventsResponse(Proof p,HttpServletRequest request,HttpServletResponse response){
294:         if(p.eventsManager==null||request.getAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE)!=p.eventsManager)throw denied();
295:         var current=p.eventsManager.getAsyncWebRequest();
296:         if(current==null){
297:             // Pure synchronous direct-boundary invocation has no HandlerAdapter wrapper. Only the
298:             // exact original preHandle pair qualifies; an installed framework current forbids it.
299:             if(response!=p.eventsResponse)throw denied();
300:         }else{
301:             // Spring6.2.7 creates this response AFTER preHandle. The pre-captured manager, exact
302:             // framework current, request, native response and direct delegate must all agree.
303:             if(current.getClass()!=StandardServletAsyncWebRequest.class)throw denied();
304:             var standard=(StandardServletAsyncWebRequest)current;
305:             if(standard.getRequest()!=p.eventsRequest||standard.getResponse()!=response
306:                     ||current.getNativeResponse(HttpServletResponse.class)!=response
307:                     ||!(response instanceof HttpServletResponseWrapper wrapper)||wrapper.getResponse()!=p.eventsResponse
308:                     ||!response.getClass().getName().equals("org.springframework.web.context.request.async.StandardServletAsyncWebRequest$LifecycleHttpServletResponse")
309:                     ||response.getClass().getClassLoader()!=StandardServletAsyncWebRequest.class.getClassLoader())throw denied();
310:         }
311:         p.eventsAsyncRequest=current;p.eventsHandlerResponse=response;
312:     }
313:     private static long eventsCursor(HttpServletRequest request){
314:         var headers=Collections.list(request.getHeaders("Last-Event-ID"));String query=request.getQueryString(),value;
315:         if(headers.size()>1||!headers.isEmpty()&&query!=null)throw denied();
316:         if(query!=null){
317:             if(!query.startsWith("Last-Event-ID="))throw denied();
318:             value=query.substring("Last-Event-ID=".length());
319:         }else value=headers.isEmpty()?"0":headers.getFirst();
320:         if(!value.matches("0|[1-9][0-9]{0,18}"))throw denied();
321:         try{return Long.parseLong(value);}catch(NumberFormatException invalid){throw denied();}
322:     }
323: 
324:     @Override
325:     public boolean preHandle(HttpServletRequest r, HttpServletResponse s, Object handler) {
326:         var p = proof(r);
327:         synchronized (p) {
328:             p.check(r);
329:             if (p.owner != this || p.ready || !(handler instanceof HandlerMethod h)
330:                     || !(p.purpose==Purpose.SESSION?ownsSession(h):owns(h)))
331:                 throw denied();
332:             if(p.purpose==Purpose.EVENTS){p.eventsRequest=r;p.eventsResponse=s;p.eventsManager=WebAsyncUtils.getAsyncManager(r);}
333:             p.ready = true;
334:         }
335:         return true;
336:     }
337: 
338:     private static void clear(HttpServletRequest r) {
339:         Object value = r.getAttribute(KEY);
340:         if (value instanceof Proof p && hasAnchor(r, p.anchor)) {
341:             synchronized (p) {
342:                 p.invalid = true;
343:                 p.eventsRequest=null;p.eventsResponse=null;
344:                 p.eventsHandlerResponse=null;p.eventsManager=null;p.eventsAsyncRequest=null;
345:             }
346:             r.removeAttribute(KEY);
347:         }
348:     }
349: 
350:     @Override
351:     public void afterCompletion(
352:             HttpServletRequest r, HttpServletResponse s, Object h, Exception e) {
353:         clear(r);
354:     }
355: 
356:     @Override
357:     public void afterConcurrentHandlingStarted(
358:             HttpServletRequest r, HttpServletResponse s, Object h) {
359:         clear(r);
360:     }
361: 
362:     private final class IngressFilter extends GenericFilterBean {
363:         @Override
364:         public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
365:                 throws IOException, ServletException {
366:             var r = (HttpServletRequest) request;
367:             var s = (HttpServletResponse) response;
368:             boolean observation=false,eventsObservation=false;
369:             try {
370:                 var purpose=route(r);
371:                 observation=purpose==Purpose.STATUS||purpose==Purpose.EVENTS;
372:                 eventsObservation=purpose==Purpose.EVENTS;
373:                 if("/auth/session".equals(r.getRequestURI()))s.setHeader("Cache-Control","no-store");
374:                 if (r.getAttribute(KEY) != null
375:                         || r.getHeader("X-Api-Key") != null
376:                         || r.getHeader("Cookie") != null) throw denied();
377:                 if(purpose==Purpose.SESSION||purpose==Purpose.STATUS||purpose==Purpose.EVENTS){
378:                     var names=Collections.list(r.getHeaderNames());
379:                     for(String name:names){String lower=name.toLowerCase(Locale.ROOT);
380:                         if(lower.startsWith("x-user-")||lower.startsWith("x-tenant")||lower.startsWith("x-principal")||lower.equals("apikey")||lower.equals("api-key")||lower.equals("proxy-authorization")||lower.startsWith("x-auth-")||lower.equals("x-subject")||lower.equals("x-resource")||lower.equals("x-target")||lower.equals("x-action"))throw denied();
381:                         if(Set.of("tenant","tenant-id","principal","principal-id","subject","resource","target","action","actor","roles","attributes","access-token","service-token").contains(lower)
382:                                 ||List.of("x-actor","x-role","x-attribute","x-identity","x-credential","x-access-token","x-service-token","x-forwarded-user","x-forwarded-tenant").stream().anyMatch(lower::startsWith))throw denied();
383:                     }
384:                     if(r.getContentLengthLong()>0||r.getHeader("Transfer-Encoding")!=null||r.getInputStream().read()!=-1)throw denied();
385:                 }
386:                 var headers = Collections.list(r.getHeaders("Authorization"));
387:                 if (headers.size() != 1 || !headers.getFirst().startsWith("Bearer "))
388:                     throw denied();
389:                 String token = headers.getFirst().substring(7);
390:                 var jwt = decoder.decode(token);
391:                 Object scopes = jwt.getClaims().get("scope");
392:                 if (!"user".equals(jwt.getClaimAsString("token_use"))
393:                         || !(scopes instanceof String text)
394:                         || !Set.of(text.split(" ")).contains("platform.access")) throw denied();
395:                 var p =
396:                         new Proof(
397:                                 GatewayIngressBoundary.this,
398:                                 r,
399:                                 token,
400:                                 new SubjectKey(jwt.getClaimAsString("tenant_id"), jwt.getSubject()),
401:                                 jwt.getExpiresAt(),purpose==Purpose.EVENTS?eventsCursor(r):0);
402:                 r.setAttribute(KEY, p);
403:                 var context = SecurityContextHolder.createEmptyContext();
404:                 context.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
405:                 SecurityContextHolder.setContext(context);
406:                 chain.doFilter(r, s);
407:             } catch (org.springframework.security.oauth2.jwt.JwtException bad) {
408:                 if(observation)rejectObservation(s,401,eventsObservation);else reject(s,401);
409:             } catch (PlatformException bad) {
410:                 if(observation&&bad.status()==503)rejectObservation(s,503,eventsObservation);else reject(s,bad.status());
411:             } finally {
412:                 clear(r);
413:             }
414:         }
415:     }
416: 
417:     private static void rejectObservation(HttpServletResponse response,int status,boolean events)throws IOException {
418:         if(!response.isCommitted()){
419:             response.setHeader("Cache-Control","no-store");response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
420:             response.getWriter().write(Json.write(new ApiResponse<>(status==401?"PORTAL_TOKEN_INVALID":events?"RUN_EVENTS_UNAVAILABLE":"RUN_STATUS_UNAVAILABLE",status==401?"门户身份已失效":events?"运行事件暂不可用":"运行状态暂不可用",null,null)));
421:         }
422:     }
423: 
424:     private static void reject(HttpServletResponse s, int status) throws IOException {
425:         if (!s.isCommitted()) {
426:             s.setHeader("Cache-Control","no-store");
427:             s.setStatus(status);
428:             s.setContentType("application/json;charset=UTF-8");
429:             s.getWriter()
430:                     .write(Json.write(new ApiResponse<>("SYS-AUTH-403", "入口未获授权", null, null)));
431:         }
432:     }
433: 
434:     private static PlatformException denied() {
435:         return new PlatformException(403, "SYS-AUTH-403", "入口未获授权");
436:     }
437: 
438:     /**
439:      * Records our actual addFilter instance and successful configure call, not an inference from
440:      * Servlet metadata.
441:      */
442:     public static final class InstalledFilterRegistration
443:             extends FilterRegistrationBean<FilterChainProxy> {
444:         public static final String NAME = "gatewayIamFilterChain";
445:         private final FilterChainProxy owned;
446:         private ServletContext context;
447:         private FilterRegistration.Dynamic added;
448:         private boolean configured;
449:         private Snapshot snapshot;
450: 
451:         private record Snapshot(
452:                 FilterChainProxy filter,
453:                 Set<String> urls,
454:                 Set<String> servlets,
455:                 Set<DispatcherType> dispatchers,
456:                 boolean async,
457:                 boolean matchAfter,
458:                 int order,
459:                 Map<String, String> init) {}
460: 
461:         public InstalledFilterRegistration(FilterChainProxy chain) {
462:             super(chain);
463:             owned = Objects.requireNonNull(chain);
464:             setName(NAME);
465:             setUrlPatterns(List.of("/*"));
466:             setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
467:             setAsyncSupported(true);
468:             setOrder(-100);
469:         }
470: 
471:         private Snapshot current() {
472:             return new Snapshot(
473:                     getFilter(),
474:                     Set.copyOf(getUrlPatterns()),
475:                     Set.copyOf(getServletNames()),
476:                     Set.copyOf(determineDispatcherTypes()),
477:                     isAsyncSupported(),
478:                     isMatchAfter(),
479:                     getOrder(),
480:                     Map.copyOf(getInitParameters()));
481:         }
482: 
483:         @Override
484:         protected FilterRegistration.Dynamic addRegistration(
485:                 String description, ServletContext context) {
486:             if (this.context != null) throw new IllegalStateException("GATEWAY_FILTER_REINSTALL");
487:             this.context = context;
488:             var result = super.addRegistration(description, context);
489:             added = result;
490:             return result;
491:         }
492: 
493:         @Override
494:         protected void configure(FilterRegistration.Dynamic registration) {
495:             super.configure(registration);
496:             if (registration != added)
497:                 throw new IllegalStateException("GATEWAY_FILTER_REGISTRATION_MISMATCH");
498:             snapshot = current();
499:             configured = true;
500:         }
501: 
502:         public void requireInstalled(ServletContext actual, FilterChainProxy chain) {
503:             var metadata = actual.getFilterRegistration(NAME);
504:             if (!configured
505:                     || context != actual
506:                     || chain != owned
507:                     || getFilter() != owned
508:                     || !isEnabled()
509:                     || !NAME.equals(getFilterName())
510:                     || !Objects.equals(snapshot, current())
511:                     || !snapshot.urls.equals(Set.of("/*"))
512:                     || !snapshot.servlets.isEmpty()
513:                     || !getServletRegistrationBeans().isEmpty()
514:                     || !snapshot.dispatchers.equals(EnumSet.allOf(DispatcherType.class))
515:                     || !snapshot.async
516:                     || snapshot.matchAfter
517:                     || snapshot.order != -100
518:                     || !snapshot.init.isEmpty()
519:                     || metadata == null
520:                     || !Set.copyOf(metadata.getUrlPatternMappings()).equals(Set.of("/*"))
521:                     || !metadata.getServletNameMappings().isEmpty())
522:                 throw new IllegalStateException("GATEWAY_FILTER_NOT_INSTALLED");
523:         }
524:     }
525: 
526:     public void requireChain(FilterChainProxy proxy, SecurityFilterChain expected) {
527:         var chains = proxy.getFilterChains();
528:         if (chains.size() != 1
529:                 || chains.getFirst() != expected
530:                 || !(chains.getFirst() instanceof DefaultSecurityFilterChain c)
531:                 || c.getRequestMatcher() != AnyRequestMatcher.INSTANCE)
532:             throw new IllegalStateException("GATEWAY_CATCHALL_CHAIN_REQUIRED");
533:         var filters = c.getFilters();
534:         int owned = filters.indexOf(filter), aware = -1;
535:         for (int i = 0; i < filters.size(); i++)
536:             if (filters.get(i) instanceof SecurityContextHolderAwareRequestFilter) aware = i;
537:         if (owned < 0 || Collections.frequency(filters, filter) != 1 || aware <= owned)
538:             throw new IllegalStateException("GATEWAY_FILTER_ORDER_INVALID");
539:     }
540: }

# platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java

1: package cn.agentplatform.gateway.api;
2: 
3: import cn.agentplatform.common.*;
4: import cn.agentplatform.common.authority.AuthorityProperties;
5: import cn.agentplatform.common.authority.GatewayIngressBoundary;
6: import cn.agentplatform.gateway.application.IntegrationAccess;
7: import cn.agentplatform.gateway.application.PublishedRunProxy;
8: import cn.agentplatform.gateway.application.RunStatusProxy;
9: import cn.agentplatform.gateway.application.RunEventsProxy;
10: import cn.agentplatform.gateway.domain.Routes;
11: 
12: import jakarta.servlet.http.*;
13: 
14: import org.springframework.core.env.Environment;
15: import org.springframework.http.client.SimpleClientHttpRequestFactory;
16: import org.springframework.web.bind.annotation.*;
17: import org.springframework.web.client.RestClient;
18: 
19: import java.io.*;
20: import java.net.*;
21: import java.nio.file.*;
22: import java.util.*;
23: 
24: /** 单一入口和有界流转发；用户JWT在网关验签后换发内部票据，不透传至外部模型/工具。 */
25: @RestController
26: public class GatewayController {
27:     private final Environment env;
28:     private final IntegrationAccess integration;
29:     private final AuthorityProperties deployment;
30:     private final Optional<PublishedRunProxy> published;
31:     private final Optional<RunStatusProxy> status;
32:     private final Optional<RunEventsProxy> events;
33: 
34:     public GatewayController(
35:             Environment env,
36:             IntegrationAccess integration,
37:             AuthorityProperties deployment,
38:             Optional<PublishedRunProxy> published) {
39:         this(env,integration,deployment,published,Optional.empty());
40:     }
41: 
42:     public GatewayController(Environment env,IntegrationAccess integration,AuthorityProperties deployment,
43:             Optional<PublishedRunProxy> published,Optional<RunStatusProxy> status) {
44:         this(env,integration,deployment,published,status,Optional.empty());
45:     }
46: 
47:     @org.springframework.beans.factory.annotation.Autowired
48:     public GatewayController(Environment env,IntegrationAccess integration,AuthorityProperties deployment,
49:             Optional<PublishedRunProxy> published,Optional<RunStatusProxy> status,Optional<RunEventsProxy> events) {
50:         this.env = env;
51:         this.integration = integration;
52:         this.deployment = Objects.requireNonNull(deployment);
53:         this.published = Objects.requireNonNull(published);
54:         this.status=Objects.requireNonNull(status);
55:         this.events=Objects.requireNonNull(events);
56:     }
57: 
58:     private String base(String service) {
59:         return env.getRequiredProperty("agent.services." + service);
60:     }
61: 
62:     @RequestMapping({"/admin/**", "/v1/**"})
63:     public void proxy(HttpServletRequest req, HttpServletResponse res) throws IOException {
64:         if ("iam".equals(deployment.mode())) {
65:             if("GET".equals(req.getMethod())&&req.getRequestURI().matches("/admin/runs/r_[0-9a-f]{32}/events")){
66:                 var source=GatewayIngressBoundary.claimEvents(req,res);
67:                 events.orElseThrow(()->new PlatformException(503,"RUN_EVENTS_UNAVAILABLE","运行事件暂不可用")).forward(req,res,source);
68:                 return;
69:             }
70:             if("GET".equals(req.getMethod())&&req.getRequestURI().matches("/admin/runs/r_[0-9a-f]{32}")){
71:                 var source=GatewayIngressBoundary.claimStatus(req);
72:                 status.orElseThrow(()->new PlatformException(503,"RUN_STATUS_UNAVAILABLE","运行状态暂不可用")).forward(req,res,source);
73:                 return;
74:             }
75:             var source = GatewayIngressBoundary.claim(req);
76:             var proxy =
77:                     published.orElseThrow(
78:                             () ->
79:                                     new PlatformException(
80:                                             503, "PUBLISHED_RUN_UNAVAILABLE", "发布运行入口尚未就绪"));
81:             proxy.start(req, res, source);
82:             return;
83:         }
84:         final String service;
85:         try {
86:             service = Routes.service(req.getRequestURI());
87:         } catch (IllegalArgumentException e) {
88:             throw new PlatformException(404, "NOT_FOUND", e.getMessage());
89:         }
90:         Actor actor = Actor.current();
91:         integration.check(req, actor);
92:         String query = req.getQueryString();
93:         String target = base(service) + req.getRequestURI() + (query == null ? "" : "?" + query);
94:         HttpURLConnection upstream =
95:                 (HttpURLConnection) URI.create(target).toURL().openConnection();
96:         upstream.setConnectTimeout(5000);
97:         upstream.setReadTimeout(650000);
98:         upstream.setRequestMethod(req.getMethod());
99:         upstream.setRequestProperty("Authorization", "Bearer " + ServiceTokens.issue(actor));
100:         if (req.getContentType() != null)
101:             upstream.setRequestProperty("Content-Type", req.getContentType());
102:         String cursor = req.getHeader("Last-Event-ID");
103:         if (cursor != null) upstream.setRequestProperty("Last-Event-ID", cursor);
104:         try {
105:             if (Set.of("POST", "PUT", "PATCH").contains(req.getMethod())) {
106:                 upstream.setDoOutput(true);
107:                 upstream.setChunkedStreamingMode(8192);
108:                 try (OutputStream out = upstream.getOutputStream()) {
109:                     copyBounded(req.getInputStream(), out, 2 * 1024 * 1024);
110:                 }
111:             }
112:             int status = upstream.getResponseCode();
113:             res.setStatus(status);
114:             if (upstream.getContentType() != null) res.setContentType(upstream.getContentType());
115:             for (String header : List.of("Content-Disposition", "ETag", "Retry-After")) {
116:                 String value = upstream.getHeaderField(header);
117:                 if (value != null) res.setHeader(header, value);
118:             }
119:             if (Objects.toString(upstream.getContentType(), "").contains("text/event-stream")) {
120:                 res.setHeader("Cache-Control", "no-cache");
121:                 res.setHeader("X-Accel-Buffering", "no");
122:             }
123:             InputStream input =
124:                     status >= 400 ? upstream.getErrorStream() : upstream.getInputStream();
125:             if (input != null)
126:                 try (input) {
127:                     byte[] buffer = new byte[8192];
128:                     int n;
129:                     while ((n = input.read(buffer)) != -1) {
130:                         res.getOutputStream().write(buffer, 0, n);
131:                         res.flushBuffer();
132:                     }
133:                 }
134:         } catch (IOException e) {
135:             if (!res.isCommitted()) {
136:                 res.setStatus(502);
137:                 res.setContentType("application/json;charset=UTF-8");
138:                 res.getWriter()
139:                         .write(
140:                                 Json.write(
141:                                         new ApiResponse<>(
142:                                                 "DEPENDENCY_UNAVAILABLE",
143:                                                 "服务暂不可用，请检查新工程服务是否启动",
144:                                                 null,
145:                                                 null)));
146:             }
147:         } finally {
148:             upstream.disconnect();
149:         }
150:     }
151: 
152:     private static void copyBounded(InputStream in, OutputStream out, int max) throws IOException {
153:         byte[] buffer = new byte[8192];
154:         int total = 0, n;
155:         while ((n = in.read(buffer)) != -1) {
156:             total += n;
157:             if (total > max) throw new IOException("请求超过安全大小");
158:             out.write(buffer, 0, n);
159:         }
160:     }
161: 
162:     @GetMapping("/v3/api-docs")
163:     public Object openapi() {
164:         Actor actor = Actor.current();
165:         var factory = new SimpleClientHttpRequestFactory();
166:         factory.setConnectTimeout(3000);
167:         factory.setReadTimeout(10000);
168:         var client = RestClient.builder().requestFactory(factory).build();
169:         Map<String, Object> paths = new LinkedHashMap<>(), schemas = new LinkedHashMap<>();
170:         List<String> unavailable = new ArrayList<>();
171:         for (String service : List.of("app", "engine", "skill", "model", "monitor")) {
172:             try {
173:                 var doc =
174:                         client.get()
175:                                 .uri(base(service) + "/v3/api-docs")
176:                                 .header("Authorization", "Bearer " + ServiceTokens.issue(actor))
177:                                 .retrieve()
178:                                 .body(Map.class);
179:                 if (doc == null) throw new IOException("空文档");
180:                 if (doc.get("paths") instanceof Map<?, ?> map)
181:                     map.forEach(
182:                             (k, v) -> {
183:                                 if (k.toString().startsWith("/internal")) return;
184:                                 // 多服务controller常见list/get同名，聚合文档必须产生全局唯一operationId。
185:                                 if (v instanceof Map<?, ?> item)
186:                                     item.forEach(
187:                                             (method, operation) -> {
188:                                                 if (operation instanceof Map<?, ?> rawOperation
189:                                                         && Set.of(
190:                                                                         "get", "post", "put",
191:                                                                         "delete", "patch",
192:                                                                         "options", "head")
193:                                                                 .contains(method.toString())) {
194:                                                     @SuppressWarnings("unchecked")
195:                                                     Map<String, Object> mutable =
196:                                                             (Map<String, Object>) rawOperation;
197:                                                     mutable.put(
198:                                                             "operationId",
199:                                                             service
200:                                                                     + "_"
201:                                                                     + method
202:                                                                     + "_"
203:                                                                     + k.toString()
204:                                                                             .replaceAll(
205:                                                                                     "[^A-Za-z0-9]+",
206:                                                                                     "_"));
207:                                                 }
208:                                             });
209:                                 paths.put(k.toString(), v);
210:                             });
211:                 if (doc.get("components") instanceof Map<?, ?> c
212:                         && c.get("schemas") instanceof Map<?, ?> map)
213:                     map.forEach((k, v) -> schemas.put(k.toString(), v));
214:             } catch (Exception e) {
215:                 unavailable.add(service);
216:             }
217:         }
218:         if (!unavailable.isEmpty())
219:             throw new PlatformException(
220:                     502,
221:                     "OPENAPI_DEPENDENCY_UNAVAILABLE",
222:                     "接口文档尚未就绪：" + String.join("、", unavailable));
223:         return Map.of(
224:                 "openapi",
225:                 "3.0.3",
226:                 "info",
227:                 Map.of("title", "智能体构建平台 API", "version", "v1.2"),
228:                 "servers",
229:                 List.of(Map.of("url", "/")),
230:                 "paths",
231:                 paths,
232:                 "components",
233:                 Map.of(
234:                         "schemas",
235:                         schemas,
236:                         "securitySchemes",
237:                         Map.of(
238:                                 "bearerAuth",
239:                                 Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"))),
240:                 "security",
241:                 List.of(Map.of("bearerAuth", List.of())));
242:     }
243: 
244:     @GetMapping(value = "/assistant.js", produces = "text/javascript")
245:     public byte[] assistant() throws IOException {
246:         Path root =
247:                 Path.of(System.getenv().getOrDefault("AGENT_ROOT", System.getProperty("user.dir")))
248:                         .toAbsolutePath()
249:                         .normalize();
250:         Path file = root.resolve("web/assistant.js/dist/assistant.js");
251:         if (!Files.isRegularFile(file))
252:             throw new PlatformException(503, "EMBED_BUNDLE_UNAVAILABLE", "嵌入包尚未构建，请先构建新工程前端");
253:         return Files.readAllBytes(file);
254:     }
255: }

# platform-gateway/src/main/java/cn/agentplatform/gateway/infra/GatewayIamAssembly.java

1: package cn.agentplatform.gateway.infra;
2: 
3: import cn.agentplatform.common.authority.*;
4: import cn.agentplatform.gateway.api.GatewayController;
5: import cn.agentplatform.gateway.api.PortalSessionController;
6: import cn.agentplatform.gateway.application.RunStatusProxy;
7: import cn.agentplatform.gateway.application.RunEventsProxy;
8: import org.springframework.beans.factory.ObjectProvider;
9: 
10: import jakarta.servlet.*;
11: 
12: import org.springframework.beans.factory.annotation.Qualifier;
13: import org.springframework.context.annotation.Bean;
14: import org.springframework.security.config.annotation.web.builders.HttpSecurity;
15: import org.springframework.security.config.http.SessionCreationPolicy;
16: import org.springframework.security.web.*;
17: import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
18: import org.springframework.web.servlet.handler.MappedInterceptor;
19: import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
20: 
21: import java.time.Clock;
22: import java.util.*;
23: 
24: /**
25:  * Explicitly imported finite assembly only. No component/configuration stereotype enables normal
26:  * application scanning.
27:  */
28: public final class GatewayIamAssembly {
29:     @Bean
30:     GatewayIngressBoundary gatewayIngressBoundary(
31:             GatewayController controller,
32:             ObjectProvider<PortalSessionController> session,
33:             ObjectProvider<RunStatusProxy> status,
34:             ObjectProvider<RunEventsProxy> events,
35:             TrustedJwtDecoder decoder,
36:             Clock clock,
37:             AuthorityProperties properties) {
38:         if (!"iam".equals(properties.mode()) || !"gateway".equals(properties.serviceId()))
39:             throw new IllegalStateException("GATEWAY_IAM_REQUIRED");
40:         return new GatewayIngressBoundary(controller,session.getIfAvailable(),status.getIfAvailable()!=null,events.getIfAvailable()!=null,decoder,clock);
41:     }
42: 
43:     @Bean
44:     SecurityFilterChain gatewayIamChain(HttpSecurity http, GatewayIngressBoundary boundary)
45:             throws Exception {
46:         return http.csrf(c -> c.disable())
47:                 .requestCache(c -> c.disable())
48:                 .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
49:                 .addFilterBefore(boundary.filter(), SecurityContextHolderAwareRequestFilter.class)
50:                 .authorizeHttpRequests(c -> c.anyRequest().authenticated())
51:                 .build();
52:     }
53: 
54:     @Bean
55:     MappedInterceptor gatewayIamInterceptor(GatewayIngressBoundary boundary) {
56:         return boundary.mappedInterceptor();
57:     }
58: 
59:     @Bean
60:     GatewayIngressBoundary.InstalledFilterRegistration gatewayIamRegistration(
61:             @Qualifier("springSecurityFilterChain") Filter actual) {
62:         if (!(actual instanceof FilterChainProxy chain))
63:             throw new IllegalStateException("GATEWAY_FRAMEWORK_CHAIN_REQUIRED");
64:         // Keep Security 6.5's actual MVC cache/path wrappers. Boot marks this exact Filter as seen.
65:         return new GatewayIngressBoundary.InstalledFilterRegistration(chain);
66:     }
67: 
68:     @Bean
69:     RouteCoverageVerifier gatewayIamCoverage(
70:             List<RequestMappingInfoHandlerMapping> mappings,
71:             GatewayIngressBoundary boundary,
72:             @Qualifier("springSecurityFilterChain") Filter actual,
73:             @Qualifier("gatewayIamChain") SecurityFilterChain expected,
74:             GatewayIngressBoundary.InstalledFilterRegistration registration,
75:             ServletContext context) {
76:         if (!(actual instanceof FilterChainProxy chain))
77:             throw new IllegalStateException("GATEWAY_FRAMEWORK_CHAIN_REQUIRED");
78:         return new RouteCoverageVerifier(
79:                 "gateway",
80:                 new ServiceRoutePolicy(),
81:                 mappings,
82:                 Set.of(),
83:                 new RouteCoverageVerifier.GatewayProxyIngress(
84:                         boundary, chain, expected, registration, context));
85:     }
86: }

# platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsSse.java

1: package cn.agentplatform.gateway.infra;
2: 
3: import cn.agentplatform.common.PlatformException;
4: import cn.agentplatform.common.authority.CurrentAuthorityClient;
5: import cn.agentplatform.common.authority.GatewayIngressBoundary.PortalEventsRequest;
6: import jakarta.servlet.*;
7: import jakarta.servlet.http.*;
8: import java.io.*;
9: import java.lang.ref.*;
10: import java.time.Duration;
11: import java.util.*;
12: import java.util.concurrent.TimeUnit;
13: import java.util.function.BiConsumer;
14: import org.apache.catalina.Valve;
15: import org.apache.catalina.valves.ValveBase;
16: import org.apache.coyote.ActionCode;
17: import org.apache.coyote.Processor;
18: import org.apache.coyote.UpgradeToken;
19: import org.apache.coyote.http11.Http11NioProtocol;
20: import org.apache.tomcat.util.net.*;
21: import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
22: import java.nio.ByteBuffer;
23: 
24: /** Explicitly assembled HTTP/1.1 NIO adapter. Only a real Session can supply an output window. */
25: public final class PortalRunEventsSse {
26:     static final int FRAME_BYTES=270374;
27:     private static final String SLOT=PortalRunEventsSse.class.getName()+".request";
28:     private static Bridge bridge;
29:     private final BiConsumer<String,Long> probe;
30:     public PortalRunEventsSse() { this(null); }
31:     PortalRunEventsSse(BiConsumer<String,Long> probe) { this.probe=probe; }
32: 
33:     /** Explicit context installation; this class has no bean, controller or scan annotations. */
34:     static Valve contextValve() { return new ContextValve(); }
35: 
36:     Handle start(HttpServletRequest request,HttpServletResponse response,PortalRunEventsHttp http,CurrentAuthorityClient authority,String source,String run,long cursor)throws IOException {
37:         return start(request,response,http,authority,source,run,cursor,null);
38:     }
39:     public Handle start(HttpServletRequest request,HttpServletResponse response,PortalRunEventsHttp http,CurrentAuthorityClient authority,PortalEventsRequest capture)throws IOException {
40:         Objects.requireNonNull(capture).requireLive(request,response);
41:         return start(request,response,http,authority,capture.sourceToken(),capture.publicRunId(),capture.cursor(),capture);
42:     }
43:     private Handle start(HttpServletRequest request,HttpServletResponse response,PortalRunEventsHttp http,CurrentAuthorityClient authority,String source,String run,long cursor,PortalEventsRequest capture)throws IOException {
44:         Objects.requireNonNull(http);
45:         RequestSlot token=null;PortalRunEventsHttp.Session session=null;PortalRunEventsHttp.Session.SseLease lease=null;State state=null;Handle handle=null;
46:         boolean asyncOwned=false;
47:         try {
48:             Object attribute=request.getAttribute(SLOT);
49:             if(!(attribute instanceof RequestSlot exact))throw new IllegalStateException("Exact controlled SSE lifecycle required");
50:             token=exact;
51:             if(capture==null)token.requireOriginal(request,response);else token.requireCaptured(request,response,capture);
52:             // One shared credit is registered before the only Session/State construction attempt.
53:             handle=token.origin.protocol.createHandle();
54:             session=http.forSse(authority,source,run,cursor,handle);
55:             session.lookupMetadata();lease=session.beginSse();handle.claimState();state=new State(handle,session,lease,token,probe);
56:             handle.attach(state);
57:             lease.cancellation(state::cancelSignal);lease.exits(state::finish);
58:             token.arm(state);
59:             state.async=request.startAsync();
60:             asyncOwned=true; // Completion may clear State.async before bootstrap's catch executes.
61:             state.async.setTimeout(0); // Independent original absolute deadlines drive every wait.
62:             state.async.addListener(state);
63:             state.worker=Thread.ofPlatform().daemon().inheritInheritableThreadLocals(false).name("portal-events-sse-reader").unstarted(state::read);
64:             state.worker.setContextClassLoader(PortalRunEventsSse.class.getClassLoader());
65:             state.worker.start();
66:             if(!state.awaitWork())throw new IOException("First verified window unavailable");
67:             synchronized(state.io) {
68:                 state.point("before-getter",0);
69:                 state.fresh();
70:                 response.setContentType("text/event-stream");
71:                 response.setCharacterEncoding("UTF-8");
72:                 response.setHeader("Cache-Control","no-cache");
73:                 state.output=response.getOutputStream();
74:                 state.point("after-getter",0);
75:                 state.fresh();
76:                 state.output.setWriteListener(state);
77:                 state.installed=true;
78:                 state.point("setter-return",0);
79:             }
80:             return handle;
81:         } catch(IOException|RuntimeException|Error failure) {
82:             if(capture!=null&&asyncOwned){
83:                 // Once async owns the response, even synchronous completion can make isAsyncStarted
84:                 // false. Never route this failure (or a cleanup failure) to MVC's JSON writer.
85:                 // Returning the existing Handle means response ownership, not successful business IO.
86:                 try{state.stop();}catch(RuntimeException|Error ignored){}
87:                 finally{
88:                     try{state.complete();}catch(RuntimeException|Error ignored){}
89:                     finally{try{session.close();}catch(RuntimeException|Error ignored){}}
90:                 }
91:                 try{org.slf4j.LoggerFactory.getLogger(PortalRunEventsSse.class).warn("Portal events async bootstrap stopped");}catch(RuntimeException|Error ignored){}
92:                 return handle;
93:             }
94:             if(state==null||state.async==null) {
95:                 if(token!=null)token.reject();
96:                 if(state!=null)state.detachUnstarted(); // Returns before any fee/Owner can be released.
97:                 if(lease!=null)lease.release();
98:                 if(session!=null)session.close(); // Http joins its actually-started transport/timer before refund.
99:             } else {
100:                 state.stop();state.complete();session.close(); // Actual completion/worker/action gates still apply.
101:             }
102:             throw failure;
103:         } finally { Reference.reachabilityFence(handle); }
104:     }
105:     public static final class Handle implements AutoCloseable {
106:         private State state;
107:         private long after;
108:         private boolean terminal;
109:         private boolean sessionAttempted,stateAttempted;
110:         private Handle() {}
111:         synchronized void claimSession() { if(sessionAttempted)throw new IllegalStateException("Unique SSE Session already attempted");sessionAttempted=true; }
112:         private synchronized void claimState() { if(!sessionAttempted||stateAttempted)throw new IllegalStateException("Unique SSE State already attempted");stateAttempted=true; }
113:         private synchronized void attach(State exact) { if(state!=null||!stateAttempted||exact.handle!=this)throw new IllegalStateException("Unique SSE State identity");state=exact; }
114:         private synchronized State active() { return state; }
115:         private synchronized void detach(State expected,long cursor,boolean terminal) { if(state==expected) { after=cursor;this.terminal=terminal;state=null; } }
116:         @Override public void close() { var current=active();if(current!=null)current.stop(); }
117:         public boolean awaitClosed(Duration timeout) { if(timeout.isNegative())throw new IllegalArgumentException("Negative close wait");var current=active();return current==null||current.finish(timeout); }
118:         long after() { var current=active();if(current!=null)synchronized(current.cell) { return current.after; }synchronized(this) { return after; } }
119:         boolean terminal() { var current=active();if(current!=null)synchronized(current.cell) { return current.terminal; }synchronized(this) { return terminal; } }
120:         void wakeForTest() { var current=active();if(current!=null)synchronized(current.cell) { current.cell.notifyAll(); } }
121:         void interruptReaderForTest() { var current=active();if(current==null)return;Thread thread;synchronized(current.cell) { thread=current.worker; }if(thread!=null)thread.interrupt(); }
122:         boolean inertTokenForTest() { var current=active();if(current==null)return true;var token=current.token;if(token==null)return true;synchronized(token) { return token.origin==null&&token.response==null&&token.originalSocket==null&&token.state==null&&token.requestThread==null; } }
123:         synchronized boolean detachedForTest() { return state==null; }
124:     }
125:     /** One fixed backing, including control frames; no UTF-8 intermediate backing or growth. */
126:     static final class Frame extends OutputStream {
127:         final byte[] bytes=new byte[FRAME_BYTES];
128:         int count;
129:         @Override public void write(int b) { if(count==bytes.length)throw limit();bytes[count++]=(byte)b; }
130:         @Override public void write(byte[] b,int off,int n) { Objects.checkFromIndexSize(off,n,b.length);if(n>bytes.length-count)throw limit();System.arraycopy(b,off,bytes,count,n);count+=n; }
131:         void ascii(String value) { for(int i=0;i<value.length();i++) { int c=value.charAt(i);if(c>127)throw limit();write(c); } }
132:         void type(String value) {
133:             if(value==null||value.isEmpty()||value.codePointCount(0,value.length())>32)throw limit();
134:             int start=count;
135:             for(int i=0;i<value.length();) {
136:                 int c=value.codePointAt(i);i+=Character.charCount(c);
137:                 if(c==0||c=='\r'||c=='\n'||c>=0xd800&&c<=0xdfff)throw limit();
138:                 if(c<128)write(c);
139:                 else if(c<2048) { write(0xc0|c>>6);write(0x80|c&63); }
140:                 else if(c<65536) { write(0xe0|c>>12);write(0x80|c>>6&63);write(0x80|c&63); }
141:                 else { write(0xf0|c>>18);write(0x80|c>>12&63);write(0x80|c>>6&63);write(0x80|c&63); }
142:             }
143:             if(count-start>128)throw limit();
144:         }
145:         void event(PortalEventsMaterialVerifier.VerifiedWindow window)throws IOException {
146:             count=0;ascii("id: ");ascii(Long.toString(window.eventSeq()));ascii("\nevent: ");type(window.eventType());ascii("\ndata: ");
147:             int start=count;window.writeRawPayload(this);if(count-start>262144)throw limit();ascii("\n\n");
148:         }
149:         void control(PortalEventsMaterialVerifier.VerifiedWindow window,boolean reset) {
150:             count=0;
151:             if(!reset)ascii("event: stream_status\ndata: {\"chunkAvailable\":false,\"chunkReplay\":false,\"recovery\":\"LAST_EVENT_ID_AND_FINAL_SNAPSHOT\"}\n\n");
152:             else {
153:                 String reason=window.resetReason();if("EXPIRED".equals(reason))reason="CURSOR_EXPIRED";
154:                 if(!Set.of("CURSOR_AHEAD","CURSOR_EXPIRED").contains(reason))throw limit();
155:                 String status=window.status();if(!status.matches("[A-Z_]{1,32}"))throw limit();
156:                 ascii("event: stream_reset\ndata: {\"reason\":\"");ascii(reason);ascii("\",\"chunkReplay\":false,\"recovery\":\"FINAL_SNAPSHOT\",\"lastEventId\":");
157:                 ascii(Long.toString(window.after()));ascii(",\"status\":\"");ascii(status);ascii("\",\"eventsWatermark\":");ascii(Long.toString(window.watermark()));ascii("}\n\n");
158:             }
159:         }
160:     }
161:     private static PlatformException limit() { return new PlatformException(413,"EVENT_LIMIT","事件响应过大"); }
162: 
163:     private static final class State implements WriteListener,AsyncListener {
164:         final Object cell=new Object(),io=new Object();
165:         RequestSlot token;
166:         final Handle handle;
167:         BiConsumer<String,Long> probe;
168:         PortalRunEventsHttp.Session session;
169:         PortalRunEventsHttp.Session.SseLease lease;
170:         AsyncContext async;
171:         ServletOutputStream output;
172:         Frame frame;
173:         Thread worker;
174:         PortalEventsMaterialVerifier.VerifiedWindow slot;
175:         final long deadline;
176:         long idleDeadline,after;
177:         boolean installed,stopping,abortIssued,eof,completed,completeRequested,finalizing,finished,terminal,statusSent,event,done,frameDone,attempted,flushed;
178:         int offset,heavy,actions;
179:         State(Handle handle,PortalRunEventsHttp.Session session,PortalRunEventsHttp.Session.SseLease lease,RequestSlot token,BiConsumer<String,Long> probe) {
180:             this.handle=handle;this.session=session;this.lease=lease;this.token=token;this.probe=probe;deadline=lease.deadline();
181:             idleDeadline=Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(15));frame=new Frame();
182:         }
183:         private void detachUnstarted() {
184:             synchronized(cell) {
185:                 if(async!=null||worker!=null||actions!=0||heavy!=0||slot!=null)throw new IllegalStateException("Unstarted SSE cleanup identity");
186:                 frame=null;output=null;token=null;session=null;lease=null;finished=true;
187:                 handle.detach(this,after,terminal);
188:             }
189:             point("unstarted-detached",0);probe=null;
190:         }
191:         private void detachHeavy() {
192:             synchronized(cell) { frame=null;output=null;slot=null;worker=null;async=null;token=null; }
193:             // A test barrier may hold the following cell lock; its callback fully returns before refund.
194:             point("before-refund",0);probe=null;
195:         }
196:         void point(String phase,long value) { var currentProbe=probe;if(currentProbe!=null)currentProbe.accept(phase,value); }
197:         void fresh() { synchronized(cell) { if(stopping||slot==null)throw new IllegalStateException("SSE output stopped");slot.requireFresh(); } }
198:         long left() { return Math.min(deadline,idleDeadline)-System.nanoTime(); }
199:         boolean awaitWork() {
200:             for(;;) {
201:                 synchronized(cell) {
202:                     if(stopping)return false;
203:                     if(slot!=null)return true;
204:                     if(eof||left()<=0)return false;
205:                 }
206:                 // Only the inert shell reaches this point; no material local or application gate.
207:                 point(installed?"ready-idle":"bootstrap-wait",0);
208:                 synchronized(cell) {
209:                     if(stopping)return false;
210:                     if(slot!=null)return true;
211:                     if(eof||left()<=0)return false;
212:                     try { TimeUnit.NANOSECONDS.timedWait(cell,left()); }
213:                     catch(InterruptedException failure) { Thread.currentThread().interrupt();return false; }
214:                 }
215:             }
216:         }
217:         void publish(PortalEventsMaterialVerifier.VerifiedWindow window,boolean isEvent)throws IOException {
218:             synchronized(cell) {
219:                 if(stopping||slot!=null)throw new IOException("SSE publication stopped");
220:                 window.requireFresh();slot=window;event=isEvent;offset=0;done=false;frameDone=false;attempted=false;flushed=false;cell.notifyAll();
221:                 boolean interrupted=false;
222:                 while(!done) {
223:                     // Stop never ends the borrow while a heavy helper is still on a callback stack.
224:                     if(stopping&&heavy==0) { slot=null;done=true;cell.notifyAll();break; }
225:                     long remaining=left();
226:                     if(remaining<=0&&heavy==0)throw new IOException("SSE original deadline");
227:                     try { TimeUnit.NANOSECONDS.timedWait(cell,remaining>0?remaining:TimeUnit.SECONDS.toNanos(1)); }
228:                     catch(InterruptedException failure) { interrupted=true;stopping=true;cell.notifyAll(); }
229:                 }
230:                 if(interrupted)Thread.currentThread().interrupt();
231:                 if(stopping)throw new IOException("SSE stopped");
232:             }
233:         }
234:         void read() {
235:             try {
236:                 session.consumeVerified(window->{
237:                     point("window",window.eventSeq()==null?0:window.eventSeq());
238:                     if(window.resetReason()!=null) {
239:                         frame.control(window,true);publish(window,false);throw new IOException("SSE reset complete");
240:                     }
241:                     if(!statusSent) { frame.control(window,false);publish(window,false);statusSent=true; }
242:                     if(window.eventSeq()!=null) { frame.event(window);publish(window,true); }
243:                     if(window.drained()) { synchronized(cell) { terminal=true; }throw new IOException("SSE terminal drained"); }
244:                 });
245:             } catch(RuntimeException failure) { /* Bounded outcome only; no exception graph retained. */ }
246:             finally {
247:                 boolean cancelled;
248:                 synchronized(cell) { eof=true;if(heavy==0) { slot=null;done=true; }cancelled=stopping;cell.notifyAll(); }
249:                 // The same sole worker is now OUTSIDE the synchronous material/Http operation.
250:                 // It can terminate a not-ready public request without needing a future callback.
251:                 if(cancelled)stop();
252:                 point("worker-body-exit",0);
253:             }
254:         }
255:         @Override public void onWritePossible()throws IOException {
256:             point("callback-entry",installed?1:0);
257:             try {
258:                 while(awaitWork()) {
259:                     boolean ready;
260:                     synchronized(cell) { heavy++; }
261:                     try { ready=writeSlot(); } // Heavy locals end before detaching the slot or ready-idle wait.
262:                     finally { synchronized(cell) {
263:                         heavy--;
264:                         if(frameDone||stopping) { slot=null;done=true;cell.notifyAll(); }
265:                     } }
266:                     if(!ready)return;
267:                 }
268:                 complete();
269:             } catch(IOException|RuntimeException failure) { stop();complete(); }
270:         }
271:         private boolean writeSlot()throws IOException {
272:             PortalEventsMaterialVerifier.VerifiedWindow window;
273:             synchronized(cell) { if(stopping||slot==null)return true;window=slot; }
274:                 while(true) {
275:                     synchronized(io) {
276:                         fresh();point("before-ready",offset);window.requireFresh();
277:                         boolean ready=output.isReady();window.requireFresh();
278:                         if(!ready) { point("ready-false",offset);return false; }
279:                         if(offset<frame.count) {
280:                             int n=Math.min(8192,frame.count-offset);
281:                             attempted=true;point("before-write",offset);window.requireFresh();
282:                             output.write(frame.bytes,offset,n);window.requireFresh();offset+=n;point("after-write",offset);
283:                             idleDeadline=Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(15));
284:                         } else if(!flushed) {
285:                             point("before-flush",offset);window.requireFresh();output.flush();window.requireFresh();flushed=true;
286:                             if(!output.isReady()) { point("flush-wait",offset);return false; }
287:                             window.requireFresh();
288:                         } else {
289:                             window.requireFresh();
290:                             if(event) { window.confirmEventFlushed();synchronized(cell) { after=window.eventSeq(); } }
291:                             frameDone=true;point("frame-complete",offset);return true;
292:                         }
293:                     }
294:                 }
295:         }
296:         void complete() {
297:             synchronized(io) { synchronized(cell) { if(completeRequested||completed)return;completeRequested=true; } }
298:             try { async.complete(); } catch(IllegalStateException ignored) { /* No completion evidence: retain Owner. */ }
299:         }
300:         void stop() {
301:             PortalRunEventsHttp.Session active;
302:             synchronized(io) {
303:                 synchronized(cell) { if(abortIssued||finished||finalizing)return;stopping=true;abortIssued=true;actions++;active=session;cell.notifyAll(); }
304:             }
305:             try {
306:                 if(active!=null)active.abort();
307:                 var currentToken=token;if(currentToken!=null)currentToken.abort(this);
308:             } finally { synchronized(cell) { actions--;cell.notifyAll(); } }
309:         }
310:         void cancelSignal() { synchronized(cell) { stopping=true;cell.notifyAll(); } }
311:         @Override public void onError(Throwable failure) {
312:             PortalRunEventsHttp.Session active;
313:             synchronized(io) { synchronized(cell) { stopping=true;active=session;cell.notifyAll(); } }
314:             if(active!=null)active.abort();
315:             complete();
316:         }
317:         @Override public void onTimeout(AsyncEvent event) { stop();complete(); }
318:         @Override public void onError(AsyncEvent event) { onError(event.getThrowable()); }
319:         @Override public void onStartAsync(AsyncEvent event) { stop();complete(); }
320:         @Override public void onComplete(AsyncEvent event) {
321:             point("completion-entry",0);
322:             var currentToken=token;if(currentToken!=null)currentToken.disarm(this);
323:             point("token-disarmed",0);
324:             synchronized(io) { synchronized(cell) { stopping=true;completed=true;cell.notifyAll(); } }
325:             finish(Duration.ofSeconds(2));
326:         }
327:         private boolean awaitWorker(long end) {
328:             Thread thread;synchronized(cell) { thread=worker; }
329:             if(thread==Thread.currentThread())return false;
330:             if(thread!=null)try { TimeUnit.NANOSECONDS.timedJoin(thread,Math.max(0,end-System.nanoTime())); }
331:             catch(InterruptedException failure) { Thread.currentThread().interrupt();return false; }
332:             return thread==null||!thread.isAlive();
333:         }
334:         boolean finish(Duration timeout) {
335:             if(timeout.isNegative())throw new IllegalArgumentException("Negative close wait");
336:             RequestSlot currentToken=token;
337:             if(Thread.holdsLock(io)||currentToken!=null&&Thread.holdsLock(currentToken))return false;
338:             long end=System.nanoTime()+timeout.toNanos();
339:             synchronized(cell) {
340:                 if(worker==Thread.currentThread())return false;
341:                 while(!finished&&(finalizing||!completed||actions!=0||heavy!=0)) {
342:                     long remaining=end-System.nanoTime();if(remaining<=0)return false;
343:                     try { TimeUnit.NANOSECONDS.timedWait(cell,remaining); }
344:                     catch(InterruptedException failure) { Thread.currentThread().interrupt();return false; }
345:                 }
346:                 if(finished)return true;
347:                 // Elect BEFORE any Thread capture: another finisher may retain only this anchored State.
348:                 finalizing=true;
349:             }
350:             try {
351:                 // Actual onComplete already published stopping. Wake original private IO as well;
352:                 // this abort helper returns before join, and releases no lease/material/Owner fee.
353:                 session.abort();
354:                 if(!awaitWorker(end))return false;
355:                 synchronized(cell) {
356:                     if(worker!=null&&worker.isAlive()||actions!=0||heavy!=0||slot!=null)return false;
357:                 }
358:                 var closing=session;
359:                 detachHeavy(); // Thread/probe/heavy-bearing helpers have actually returned.
360:                 if(lease!=null) { lease.release();lease=null; }
361:                 closing.close();boolean result=closing.awaitClosed(Duration.ofNanos(Math.max(0,end-System.nanoTime())));
362:                 synchronized(cell) {
363:                     if(result) { session=null;token=null;probe=null;handle.detach(this,after,terminal); }
364:                     finished=result;
365:                 }
366:                 return result;
367:             } finally { synchronized(cell) { finalizing=false;cell.notifyAll(); } }
368:         }
369:     }
370:     private static final class RequestSlot {
371:         private org.apache.catalina.connector.Response response;
372:         private WrappedProcessor origin;
373:         private SocketWrapperBase<?> originalSocket;
374:         private State state;
375:         private Thread requestThread;
376:         private boolean armed,used,claimed;
377:         RequestSlot(org.apache.catalina.connector.Response response,WrappedProcessor origin,SocketWrapperBase<?> socket) { this.response=response;this.origin=origin;originalSocket=socket;requestThread=Thread.currentThread(); }
378:         synchronized void requireOriginal(HttpServletRequest request,HttpServletResponse supplied) {
379:             requireScope(request);
380:             if(response.getResponse()!=supplied||response.getRequest().getRequest()!=request)
381:                 throw new IllegalStateException("Original controlled REQUEST scope required");
382:             claimed=true;
383:         }
384:         synchronized void requireCaptured(HttpServletRequest request,HttpServletResponse supplied,PortalEventsRequest capture){
385:             capture.requireLive(request,supplied);requireScope(request);
386:             // Security/MVC supplies wrappers. Only the captured exact argument pair may use this
387:             // public Servlet-wrapper adaptation; stream acquisition always uses supplied above.
388:             if(!contains(request,response.getRequest().getRequest())||!contains(supplied,response.getResponse()))
389:                 throw new IllegalStateException("Original controlled MVC facades required");
390:             claimed=true;
391:         }
392:         private void requireScope(HttpServletRequest request){
393:             if(request.getDispatcherType()!=DispatcherType.REQUEST||requestThread!=Thread.currentThread()||bridge==null||bridge.current()!=origin||origin==null||origin.slot!=this||origin.socket!=originalSocket||originalSocket==null||response==null||armed||used||claimed)
394:                 throw new IllegalStateException("Original controlled REQUEST scope required");
395:         }
396:         private static boolean contains(ServletRequest supplied,ServletRequest exact){
397:             for(int i=0;i<32&&supplied!=null;i++){
398:                 if(supplied==exact)return true;
399:                 if(!(supplied instanceof ServletRequestWrapper wrapper))return false;
400:                 supplied=wrapper.getRequest();
401:             }return false;
402:         }
403:         private static boolean contains(ServletResponse supplied,ServletResponse exact){
404:             for(int i=0;i<32&&supplied!=null;i++){
405:                 if(supplied==exact)return true;
406:                 if(!(supplied instanceof ServletResponseWrapper wrapper))return false;
407:                 supplied=wrapper.getResponse();
408:             }return false;
409:         }
410:         synchronized void reject() { if(origin!=null&&origin.slot==this)origin.slot=null;invalidate(); }
411:         synchronized void arm(State state) { if(armed||used||response==null)throw new IllegalStateException("SSE request token unavailable");this.state=state;armed=true; }
412:         void abort(State expected) {
413:             // Never wait for the real lifecycle exclusion while holding an application monitor.
414:             SocketWrapperBase<?> original;WrappedProcessor processor;
415:             synchronized(this) { original=originalSocket;processor=origin;if(original==null||processor==null)return; }
416:             var lock=original.getLock();lock.lock();
417:             try { synchronized(expected.io) { synchronized(this) {
418:                 if(!armed||used||state!=expected||response==null||origin!=processor||originalSocket!=original||processor.socket!=original||processor.slot!=this)return;
419:                 used=true;expected.point("before-close-now",0);
420:                 response.getCoyoteResponse().action(ActionCode.CLOSE_NOW,new IOException("SSE terminated"));
421:                 expected.point("close-now-return",0);
422:             } } } finally { lock.unlock(); }
423:         }
424:         synchronized void disarm(State expected) { if(state==expected) { if(origin!=null&&origin.slot==this)origin.slot=null;invalidate(); } }
425:         synchronized void invalidate() { armed=false;state=null;response=null;origin=null;originalSocket=null;requestThread=null; }
426:     }
427:     private static final class ContextValve extends ValveBase {
428:         ContextValve() { super(true); }
429:         @Override public void invoke(org.apache.catalina.connector.Request request,org.apache.catalina.connector.Response response)throws IOException,ServletException {
430:             WrappedProcessor current=bridge==null?null:bridge.current();
431:             if(request.getDispatcherType()==DispatcherType.REQUEST&&current!=null) {
432:                 current.invalidate();request.removeAttribute(SLOT); // Replace the one current attribute; never retain token history.
433:                 if("GET".equals(request.getMethod())&&request.getRequestURI().matches("/admin/runs/r_[0-9a-f]{32}/events")) {
434:                     var token=new RequestSlot(response,current,current.socket);current.slot=token;request.setAttribute(SLOT,token);
435:                 }
436:             }
437:             getNext().invoke(request,response);
438:         }
439:     }
440:     /** Fixed Tomcat10.1.41 HTTP/1.1 NIO profile; one instance for the whole JVM lifetime. */
441:     public static final class ControlledProtocol extends Http11NioProtocol {
442:         private static boolean constructed;
443:         private long creations,recycles,processes,lastProcessor,allocations,returned;
444:         private static final int BUNDLES=1536;
445:         private static final byte FREE=0,RESERVED=1,TRACKED=2,BURNED=3;
446:         private Reference<?>[] references;
447:         private byte[] slots;
448:         private ReferenceQueue<Object> queue;
449:         private boolean corrupt;
450:         private int failurePoint,handleFailurePoint;
451:         private long handleAllocations,handleReturned,processorReturned;
452:         public ControlledProtocol() {
453:             synchronized(ControlledProtocol.class) {
454:                 if(constructed)throw new IllegalStateException("Only one controlled connector per JVM lifetime");
455:                 PortalEventsPacketBudget.SHARED.reserveConnectorBaseline();
456:                 constructed=true;bridge=new Bridge();references=new Reference<?>[BUNDLES];slots=new byte[BUNDLES];queue=new ReferenceQueue<>();
457:             }
458:             setMaxConnections(1024);setMaxThreads(64);setProcessorCache(64);
459:         }
460:         private void verify(boolean started) {
461:             if(getMaxConnections()!=1024||getMaxThreads()!=64||getProcessorCache()!=64||getEndpoint().getUseVirtualThreads()||findUpgradeProtocols().length!=0)
462:                 throw new IllegalStateException("Controlled HTTP/1.1 NIO profile changed");
463:             var executor=getExecutor();
464:             if(executor!=null&&!(executor instanceof org.apache.tomcat.util.threads.ThreadPoolExecutor pool&&pool.getMaximumPoolSize()==64)||started&&executor==null)
465:                 throw new IllegalStateException("Controlled internal executor required");
466:         }
467:         @Override public void setExecutor(java.util.concurrent.Executor executor) { if(executor!=null)throw new IllegalArgumentException("Custom executor prohibited");super.setExecutor(null); }
468:         @Override public void start()throws Exception { verify(false);super.start();verify(true); }
469:         // Both public paths use the ORIGINAL key. A historical CHM node must not own a wrapper.
470:         private Processor waitingKey(Processor processor) {
471:             if(processor instanceof WrappedProcessor owned) {
472:                 if(owned.protocol!=this)throw new IllegalArgumentException("Foreign controlled processor");
473:                 return owned.delegate;
474:             }
475:             return processor;
476:         }
477:         @Override public void addWaitingProcessor(Processor processor) { super.addWaitingProcessor(waitingKey(processor)); }
478:         @Override public void removeWaitingProcessor(Processor processor) { super.removeWaitingProcessor(waitingKey(processor)); }
479:         @Override protected synchronized Processor createProcessor() {
480:             verify(true);drainReferences(); // This reference-bearing frame fully RETURNS before credit is used.
481:             if(corrupt)throw new IllegalStateException("Controlled allocation identity failed");
482:             int index=0;while(index<BUNDLES&&slots[index]!=FREE)index++;
483:             if(index==BUNDLES)throw new IllegalStateException("Controlled processor allocation exhausted");
484:             slots[index]=RESERVED;int injectedFailure=failurePoint;failurePoint=0;
485:             try {
486:                 Processor original=super.createProcessor();
487:                 var wrapped=new WrappedProcessor(this,original,creations+1);allocations++;
488:                 if(injectedFailure==1)throw new IllegalStateException("Controlled construction failure probe");
489:                 var reference=new SlotReference(original,queue,index);references[index]=reference;
490:                 if(injectedFailure==2)throw new IllegalStateException("Controlled registration failure probe");
491:                 slots[index]=TRACKED;creations++;
492:                 Reference.reachabilityFence(original);Reference.reachabilityFence(wrapped);
493:                 return wrapped;
494:             } catch(RuntimeException|Error failure) { slots[index]=BURNED;throw failure; }
495:         }
496:         private synchronized Handle createHandle() {
497:             drainReferences();int index=freeIndex();slots[index]=RESERVED;
498:             int injectedFailure=handleFailurePoint;handleFailurePoint=0;
499:             Handle handle=null;
500:             try {
501:                 handle=new Handle();handleAllocations++;
502:                 if(injectedFailure==1)throw new IllegalStateException("Controlled Handle construction failure probe");
503:                 references[index]=new HandleReference(handle,queue,index);
504:                 if(injectedFailure==2)throw new IllegalStateException("Controlled Handle registration failure probe");
505:                 slots[index]=TRACKED;return handle;
506:             } catch(RuntimeException|Error failure) { slots[index]=BURNED;throw failure; }
507:             finally { Reference.reachabilityFence(handle); }
508:         }
509:         private int freeIndex() {
510:             if(corrupt)throw new IllegalStateException("Controlled allocation identity failed");
511:             int index=0;while(index<BUNDLES&&slots[index]!=FREE)index++;
512:             if(index==BUNDLES)throw new IllegalStateException("Controlled shared allocation exhausted");
513:             return index;
514:         }
515:         private void drainReferences() {
516:             for(Reference<?> reference;(reference=queue.poll())!=null;) {
517:                 int index;
518:                 if(reference instanceof SlotReference exact)index=exact.index;
519:                 else if(reference instanceof HandleReference exact)index=exact.index;
520:                 else { corrupt=true;return; }
521:                 if(index<0||index>=BUNDLES||references[index]!=reference) { corrupt=true;return; }
522:                 if(slots[index]==BURNED)continue;
523:                 if(slots[index]!=TRACKED) { corrupt=true;return; }
524:                 references[index]=null;slots[index]=FREE;returned++;
525:                 if(reference instanceof HandleReference)handleReturned++;else processorReturned++;
526:             }
527:         }
528:         synchronized void failHandleConstructionForTest(int point) { if(point<1||point>2)throw new IllegalArgumentException();handleFailurePoint=point; }
529:         synchronized long handleAllocationsForTest() { return handleAllocations; }
530:         synchronized long handleReturnedForTest() { drainReferences();return handleReturned; }
531:         synchronized long processorReturnedForTest() { drainReferences();return processorReturned; }
532:         synchronized void failConstructionForTest(int point) { if(point<1||point>2)throw new IllegalArgumentException();failurePoint=point; }
533:         synchronized long allocationsForTest() { return allocations; }
534:         synchronized int burnedForTest() { int n=0;for(byte state:slots)if(state==BURNED)n++;return n; }
535:         synchronized long returnedForTest() { drainReferences();return returned; }
536:         boolean waitingIdentityForTest(Processor processor) {
537:             Processor original=waitingKey(processor);int before=getWaitingProcessorCount();
538:             addWaitingProcessor(processor);super.removeWaitingProcessor(original);
539:             if(getWaitingProcessorCount()!=before)return false;
540:             super.addWaitingProcessor(original);removeWaitingProcessor(processor);
541:             return getWaitingProcessorCount()==before;
542:         }
543:         synchronized long creationsForTest() { return creations; }
544:         synchronized long recyclesForTest() { return recycles; }
545:         synchronized long processorForTest() { return lastProcessor; }
546:         SocketWrapperBase<?> currentSocketForTest() { var current=bridge.current();return current==null?null:current.socket; }
547:     }
548:     /** The only referent edge is PhantomReference's GC-managed field; no delegate/history copy. */
549:     private static final class SlotReference extends PhantomReference<Processor> {
550:         private final int index;
551:         SlotReference(Processor original,ReferenceQueue<Object> queue,int index) { super(original,queue);this.index=index; }
552:     }
553:     private static final class HandleReference extends PhantomReference<Handle> {
554:         private final int index;
555:         HandleReference(Handle handle,ReferenceQueue<Object> queue,int index) { super(handle,queue);this.index=index; }
556:     }
557:     /** Fixed64 short scopes; no per-event object, task, permission or connection registry. */
558:     private static final class Bridge {
559:         final Thread[] threads=new Thread[64];
560:         final WrappedProcessor[] processors=new WrappedProcessor[64];
561:         synchronized WrappedProcessor current() { Thread thread=Thread.currentThread();for(int i=0;i<64;i++)if(threads[i]==thread)return processors[i];return null; }
562:     }
563:     private static final class WrappedProcessor implements Processor {
564:         private final ControlledProtocol protocol;
565:         private final Processor delegate;
566:         private final long id;
567:         private volatile SocketWrapperBase<?> socket;
568:         private volatile RequestSlot slot;
569:         WrappedProcessor(ControlledProtocol protocol,Processor delegate,long id) { this.protocol=protocol;this.delegate=delegate;this.id=id; }
570:         void invalidate() { var previous=slot;if(previous!=null)previous.invalidate();slot=null; }
571:         @Override public SocketState process(SocketWrapperBase<?> actual,SocketEvent event)throws IOException {
572:             var lock=actual.getLock();lock.lock();int index=-1;WrappedProcessor previous=null;
573:             try {
574:                 protocol.verify(true);socket=actual;
575:                 synchronized(bridge) {
576:                     Thread thread=Thread.currentThread();
577:                     for(int i=0;i<64;i++)if(bridge.threads[i]==thread) { index=i;break; }
578:                     if(index<0)for(int i=0;i<64;i++)if(bridge.threads[i]==null) { index=i;break; }
579:                     if(index<0)throw new IOException("Controlled processing scopes exhausted");
580:                     previous=bridge.processors[index];bridge.threads[index]=thread;bridge.processors[index]=this;
581:                 }
582:                 synchronized(protocol) { protocol.processes++;protocol.lastProcessor=id; }
583:                 SocketState result=delegate.process(actual,event);
584:                 if(result!=SocketState.LONG&&result!=SocketState.ASYNC_END)invalidate();
585:                 return result;
586:             } catch(IOException|RuntimeException|Error failure) { invalidate();throw failure; }
587:             finally {
588:                 if(index>=0)synchronized(bridge) { bridge.processors[index]=previous;if(previous==null)bridge.threads[index]=null; }
589:                 lock.unlock();
590:             }
591:         }
592:         @Override public void recycle() {
593:             SocketWrapperBase<?> old=socket;
594:             if(old==null) { invalidate();protocol.removeWaitingProcessor(this);delegate.recycle();return; }
595:             var lock=old.getLock();lock.lock();
596:             try {
597:                 // Off-callback close/release cannot pool or reuse the delegate across an old action.
598:                 State retiring=slot==null?null:slot.state;
599:                 invalidate();if(retiring!=null)retiring.point("recycle-invalidated",0);
600:                 protocol.removeWaitingProcessor(this);delegate.recycle();socket=null;
601:                 synchronized(protocol) { protocol.recycles++; }
602:             } finally { lock.unlock(); }
603:         }
604:         @Override public UpgradeToken getUpgradeToken() { return delegate.getUpgradeToken(); }
605:         @Override public boolean isUpgrade() { return delegate.isUpgrade(); }
606:         @Override public boolean isAsync() { return delegate.isAsync(); }
607:         @Override public void timeoutAsync(long now) { delegate.timeoutAsync(now); }
608:         @Override public org.apache.coyote.Request getRequest() { return delegate.getRequest(); }
609:         @Override public void setSslSupport(SSLSupport ssl) { delegate.setSslSupport(ssl); }
610:         @Override public ByteBuffer getLeftoverInput() { return delegate.getLeftoverInput(); }
611:         @Override public void pause() { delegate.pause(); }
612:         @Override public boolean checkAsyncTimeoutGeneration() { return delegate.checkAsyncTimeoutGeneration(); }
613:     }
614: }

# platform-gateway/src/main/java/cn/agentplatform/gateway/application/RunEventsProxy.java

1: package cn.agentplatform.gateway.application;
2: 
3: import cn.agentplatform.common.PlatformException;
4: import cn.agentplatform.common.authority.*;
5: import cn.agentplatform.gateway.infra.*;
6: import jakarta.servlet.http.*;
7: import java.io.IOException;
8: import java.util.Objects;
9: 
10: /** Only synchronous bootstrap; the existing SSE lifecycle owns every asynchronous resource. */
11: public final class RunEventsProxy {
12:     private final PortalRunEventsSse sse;
13:     private final PortalRunEventsHttp http;
14:     private final CurrentAuthorityClient authority;
15:     public RunEventsProxy(PortalRunEventsSse sse,PortalRunEventsHttp http,CurrentAuthorityClient authority){
16:         this.sse=Objects.requireNonNull(sse);this.http=Objects.requireNonNull(http);this.authority=Objects.requireNonNull(authority);
17:     }
18:     public void forward(HttpServletRequest request,HttpServletResponse response,GatewayIngressBoundary.PortalEventsRequest capture)throws IOException {
19:         try{sse.start(request,response,http,authority,capture);}
20:         catch(PlatformException failure){
21:             if(failure.status()==401)throw new PlatformException(401,"PORTAL_TOKEN_INVALID","门户身份已失效");
22:             if(failure.status()==404)throw new PlatformException(404,"RUN_NOT_FOUND","运行不存在");
23:             if(failure.status()==403)throw new PlatformException(403,failure.code(),"当前授权不允许读取运行事件");
24:             throw unavailable();
25:         }catch(IOException|RuntimeException failure){throw unavailable();}
26:     }
27:     private static PlatformException unavailable(){return new PlatformException(503,"RUN_EVENTS_UNAVAILABLE","运行事件暂不可用");}
28: }

# platform-gateway/src/main/java/cn/agentplatform/gateway/infra/GatewayPortalEventsConfiguration.java

1: package cn.agentplatform.gateway.infra;
2: 
3: import cn.agentplatform.common.authority.CurrentAuthorityClient;
4: import cn.agentplatform.gateway.application.RunEventsProxy;
5: import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
6: import org.springframework.boot.web.server.WebServerFactoryCustomizer;
7: import org.springframework.context.annotation.Bean;
8: 
9: /** Explicit import only, like GatewayIamAssembly. Fixed TLS and trusted IAM must already exist. */
10: public final class GatewayPortalEventsConfiguration {
11:     @Bean PortalRunEventsHttp portalRunEventsHttp(PortalEventsTls tls){return new PortalRunEventsHttp(tls);}
12:     @Bean PortalRunEventsSse portalRunEventsSse(){return new PortalRunEventsSse();}
13:     @Bean RunEventsProxy runEventsProxy(PortalRunEventsSse sse,PortalRunEventsHttp http,CurrentAuthorityClient authority){return new RunEventsProxy(sse,http,authority);}
14:     @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> portalEventsTomcat(){
15:         return factory->{
16:             // Boot creates this actual protocol before connector customizers apply server.tomcat.*.
17:             // ControlledProtocol.start checks the final profile, never correcting bad configuration.
18:             factory.setProtocol(PortalRunEventsSse.ControlledProtocol.class.getName());
19:             factory.addContextCustomizers(context->context.getPipeline().addValve(PortalRunEventsSse.contextValve()));
20:         };
21:     }
22: }

# admin-common/src/test/java/cn/agentplatform/common/authority/GatewayEventsIngressBoundaryTest.java

1: package cn.agentplatform.common.authority;
2: 
3: import cn.agentplatform.common.PlatformException;
4: import jakarta.servlet.http.*;
5: import java.lang.reflect.Modifier;
6: import org.junit.jupiter.api.Test;
7: import org.springframework.mock.web.*;
8: import static org.assertj.core.api.Assertions.*;
9: 
10: class GatewayEventsIngressBoundaryTest {
11:     @Test void ordinaryAttributesAndWrappersCannotMintEventsCapture(){
12:         var request=new MockHttpServletRequest("GET","/admin/runs/r_"+"a".repeat(32)+"/events");
13:         var response=new MockHttpServletResponse();
14:         for(Object fake:new Object[]{new Object(),"true",Boolean.TRUE}){
15:             request.setAttribute(GatewayIngressBoundary.class.getName()+".proof",fake);
16:             assertThatThrownBy(()->GatewayIngressBoundary.claimEvents(request,response)).isInstanceOf(PlatformException.class);
17:             assertThatThrownBy(()->GatewayIngressBoundary.claimEvents(new HttpServletRequestWrapper(request),new HttpServletResponseWrapper(response))).isInstanceOf(PlatformException.class);
18:         }
19:         request.removeAttribute(GatewayIngressBoundary.class.getName()+".proof");
20:         assertThatThrownBy(()->GatewayIngressBoundary.claimEvents(request,response)).isInstanceOf(PlatformException.class);
21:     }
22:     @Test void eventsCapabilityHasOnlyPrivateConstruction(){
23:         assertThat(GatewayIngressBoundary.PortalEventsRequest.class.getDeclaredConstructors()).allSatisfy(c->assertThat(Modifier.isPrivate(c.getModifiers())).isTrue());
24:         assertThat(GatewayIngressBoundary.PortalEventsRequest.class.getMethods()).noneMatch(m->m.getReturnType()==cn.agentplatform.common.Actor.class);
25:     }
26: }

# platform-gateway/src/test/java/cn/agentplatform/gateway/infra/GatewayPortalEventsServletTest.java

1: package cn.agentplatform.gateway.infra;
2: 
3: import cn.agentplatform.common.*;
4: import cn.agentplatform.common.authority.*;
5: import cn.agentplatform.gateway.api.*;
6: import cn.agentplatform.gateway.application.*;
7: import jakarta.servlet.*;
8: import jakarta.servlet.http.*;
9: import java.io.*;
10: import java.net.*;
11: import java.net.http.*;
12: import java.nio.charset.StandardCharsets;
13: import java.nio.file.*;
14: import java.time.*;
15: import java.util.*;
16: import java.util.concurrent.*;
17: import java.util.concurrent.atomic.*;
18: import java.util.function.*;
19: import org.junit.jupiter.api.Test;
20: import org.springframework.boot.*;
21: import org.springframework.boot.autoconfigure.*;
22: import org.springframework.boot.web.servlet.FilterRegistrationBean;
23: import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
24: import org.springframework.boot.web.embedded.tomcat.*;
25: import org.springframework.context.annotation.*;
26: import org.springframework.core.env.StandardEnvironment;
27: import org.springframework.mock.web.*;
28: import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
29: import org.springframework.web.method.HandlerMethod;
30: import org.springframework.web.context.request.async.*;
31: import static org.junit.jupiter.api.Assertions.*;
32: import static cn.agentplatform.gateway.infra.PortalEventsMaterialAuthorityTest.*;
33: 
34: /** Isolated explicit launcher; default GatewayApplication and real joined E/PG are outside B1. */
35: public class GatewayPortalEventsServletTest {
36:     static LoopbackAuthority iam;
37:     static PortalEventsTls endpoint;
38:     static CurrentAuthorityClient client;
39:     static String mode;
40:     static Path activeRun;
41:     static final AtomicInteger getters=new AtomicInteger(),writers=new AtomicInteger(),asyncGetters=new AtomicInteger(),completionThrows=new AtomicInteger();
42:     static final AtomicInteger requests=new AtomicInteger();
43:     @Configuration(proxyBeanMethods=false)
44:     @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class)
45:     @EnableWebSecurity
46:     @Import({GatewayController.class,GatewayIamAssembly.class,PlatformErrors.class})
47:     static class Receiver {
48:         @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor properties(){return factory->factory.registerSingleton("authorityProperties",iam.config("gateway"));}
49:         @Bean Clock clock(){return iam.clock();}
50:         @Bean(destroyMethod="close") TrustedJwtDecoder decoder(AuthorityProperties p){return new TrustedJwtDecoder(p,iam.clock(),true);}
51:         @Bean IntegrationAccess integration(TrustedJwtDecoder decoder){return new IntegrationAccess(iam.issuer(),iam.issuer(),decoder);}
52:         @Bean FilterRegistrationBean<Filter> observe(){
53:             var registration=new FilterRegistrationBean<Filter>((request,response,chain)->{
54:                 requests.incrementAndGet();
55:                 var supplied=new HttpServletResponseWrapper((HttpServletResponse)response){
56:                     @Override public PrintWriter getWriter()throws IOException{writers.incrementAndGet();return super.getWriter();}
57:                     @Override public ServletOutputStream getOutputStream()throws IOException{
58:                         getters.incrementAndGet();if(request.isAsyncStarted())asyncGetters.incrementAndGet();
59:                         if(mode.startsWith("postasync")){
60:                             Files.writeString(activeRun.resolve("getter-"+getters.get()+".txt"),"asyncStarted="+request.isAsyncStarted()+"\n"+String.join("\n",Arrays.stream(Thread.currentThread().getStackTrace()).map(StackTraceElement::toString).toList()));
61:                             if(request.isAsyncStarted())throw new IllegalStateException("owned getter failure");
62:                         }
63:                         return super.getOutputStream();
64:                     }
65:                 };
66:                 HttpServletRequest actual=(HttpServletRequest)request;
67:                 if(mode.startsWith("postasync-cleanup"))actual=new HttpServletRequestWrapper(actual){
68:                     @Override public AsyncContext startAsync(){return throwingCompletion(super.startAsync());}
69:                 };
70:                 chain.doFilter(actual,supplied);
71:             });registration.setOrder(-200);registration.setAsyncSupported(true);return registration;
72:         }
73:     }
74:     @Configuration(proxyBeanMethods=false)
75:     @Import({Receiver.class,GatewayPortalEventsConfiguration.class})
76:     static class EventsReceiver {
77:         @Bean PortalEventsTls tls(){return endpoint;}
78:         @Bean CurrentAuthorityClient authority(){return client;}
79:         @Bean org.springframework.boot.web.server.WebServerFactoryCustomizer<TomcatServletWebServerFactory> customExecutorProbe(){
80:             return factory->{if(mode.equals("bad-custom-executor"))factory.addConnectorCustomizers(connector->((org.apache.coyote.AbstractProtocol<?>)connector.getProtocolHandler()).setExecutor(Runnable::run));};
81:         }
82:     }
83:     static AsyncContext throwingCompletion(AsyncContext actual){
84:         return new AsyncContext(){
85:             public ServletRequest getRequest(){return actual.getRequest();}
86:             public ServletResponse getResponse(){return actual.getResponse();}
87:             public boolean hasOriginalRequestAndResponse(){return actual.hasOriginalRequestAndResponse();}
88:             public void dispatch(){actual.dispatch();}
89:             public void dispatch(String path){actual.dispatch(path);}
90:             public void dispatch(ServletContext context,String path){actual.dispatch(context,path);}
91:             public void complete(){actual.complete();completionThrows.incrementAndGet();if(mode.endsWith("-error"))throw new AssertionError("owned completion returned then error");throw new UnsupportedOperationException("owned completion returned then threw");}
92:             public void start(Runnable task){actual.start(task);}
93:             public void addListener(AsyncListener listener){actual.addListener(listener);}
94:             public void addListener(AsyncListener listener,ServletRequest request,ServletResponse response){actual.addListener(listener,request,response);}
95:             public <T extends AsyncListener>T createListener(Class<T> type)throws ServletException{return actual.createListener(type);}
96:             public void setTimeout(long timeout){actual.setTimeout(timeout);}
97:             public long getTimeout(){return actual.getTimeout();}
98:         };
99:     }
100:     static ServletWebServerApplicationContext start(Path run,boolean events){
101:         var app=new SpringApplication(events?EventsReceiver.class:Receiver.class);app.setAdditionalProfiles("test");
102:         var values=new HashMap<String,Object>();
103:         values.put("server.address","127.0.0.1");values.put("server.port","0");values.put("server.tomcat.basedir",run.resolve("tomcat").toString());
104:         values.put("server.tomcat.threads.max",mode.equals("bad-threads")?65:64);
105:         values.put("server.tomcat.max-connections",mode.equals("bad-connections")?1025:1024);
106:         values.put("server.tomcat.processor-cache",mode.equals("bad-cache")?65:64);
107:         values.put("server.http2.enabled",mode.equals("bad-http2"));
108:         values.put("spring.config.location","optional:classpath:/c2b1-no-config/");values.put("spring.main.banner-mode","off");values.put("logging.level.root","ERROR");
109:         values.put("springdoc.api-docs.enabled",false);values.put("management.endpoints.web.exposure.exclude","*");values.put("management.endpoints.web.discovery.enabled",false);
110:         app.setDefaultProperties(values);return (ServletWebServerApplicationContext)app.run();
111:     }
112:     @Test void actualControllerSecurityChainHasExactEventsIngress()throws Exception{
113:         for(String group:System.getProperty("c2b1.groups","default,header,query,postasync,postasync-cleanup,postasync-cleanup-error,q403,qtransport,no-proxy").split(","))child(group);
114:     }
115:     @Test void exactFrameworkResponseBindingRejectsForeignManagersAndWrappers()throws Exception{child("framework-boundary");}
116:     @Test void exactCaptureCursorAndPurposeDenialsPrecedeSessionAndQuery()throws Exception{child("boundary");}
117:     @Test void actualBootFinalControlledProfileRejectsOverrides()throws Exception{
118:         for(String group:List.of("bad-threads","bad-connections","bad-cache","bad-http2","bad-custom-executor"))child(group);
119:     }
120:     @Test void originalRawServletBootstrapRegressionUsesOnlyOwnedRunRoot()throws Exception{child("raw-sequence");}
121:     static void child(String group)throws Exception{
122:         Path run=Files.createDirectories(root().resolve(".local/iam-o2b-f2c2b1-events-wiring/peers")).resolve(group+"-"+UUID.randomUUID());Files.createDirectory(run);
123:         var args=List.of("-Djdk.net.unixdomain.tmpdir=V:/.local/tmp","-Djdk.tls.maxHandshakeMessageSize=32768","-Djdk.tls.maxCertificateChainLength=4","-Dcom.sun.security.enableAIAcaIssuers=false","-Djdk.tls.server.newSessionTicketCount=0","-Djdk.tls.server.enableStatusRequestExtension=false","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),GatewayPortalEventsServletTest.class.getName(),group,run.toString());
124:         Files.writeString(run.resolve("client.args"),String.join("\n",args));
125:         var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin/java.exe").toString());command.addAll(args);
126:         Process process=new ProcessBuilder(command).directory(root().toFile()).redirectErrorStream(true).start();
127:         Files.writeString(run.resolve("launch.properties"),"pid="+process.pid()+"\ncwd="+root()+"\n");
128:         var error=new AtomicReference<Throwable>();
129:         Thread collector=Thread.ofPlatform().daemon().start(()->{
130:             try(var out=Files.newOutputStream(run.resolve("client.out"));var in=process.getInputStream()){
131:                 byte[] buffer=new byte[4096];int n,total=0;
132:                 while((n=in.read(buffer))!=-1){if((total+=n)>131072)throw new IOException("bounded child output");out.write(buffer,0,n);}
133:             }catch(Throwable failure){error.set(failure);process.destroyForcibly();}
134:         });
135:         try{
136:             assertTrue(process.waitFor(60,TimeUnit.SECONDS),"owned child deadline");collector.join(3000);assertFalse(collector.isAlive());assertNull(error.get());
137:             assertEquals(0,process.exitValue(),new String(Files.readAllBytes(run.resolve("client.out")),StandardCharsets.UTF_8));
138:         }finally{
139:             if(process.isAlive())process.destroyForcibly();assertTrue(process.waitFor(3,TimeUnit.SECONDS));collector.join(3000);assertFalse(collector.isAlive());
140:             Files.writeString(run.resolve("exit.properties"),"pid="+process.pid()+"\nexit="+process.exitValue()+"\nalive="+process.isAlive()+"\ncollectorAlive="+collector.isAlive()+"\nwaitForReturned=true\n");
141:         }
142:     }
143:     public static void main(String[] args)throws Exception{
144:         mode=args[0];Path run=Path.of(args[1]);activeRun=run;
145:         if(mode.equals("raw-sequence")){PortalRunEventsSseLifecycleTest.scenario("sequence",run);return;}
146:         if(mode.equals("boundary")){boundary(run);return;}
147:         if(mode.equals("framework-boundary")){frameworkBoundary(run);return;}
148:         if(mode.equals("no-proxy")){noProxy(run);return;}
149:         if(mode.startsWith("bad-")){badProfile(run);return;}
150:         boot(run);
151:     }
152:     static void boot(Path run)throws Exception{
153:         try(var f=new Fixture();var peer=new PortalRunEventsSseLifecycleTest.SequencePeer(f,run,m->{},mode.equals("q403")?"q403":mode.equals("qtransport")?"qtransport":"sequence",utf("null"));var tls=peer.tls()){
154:             iam=f.iam;endpoint=tls;client=f.gateway;
155:             try(var context=start(run,true);var http=HttpClient.newHttpClient()){
156:                 assertSame(PortalRunEventsSse.class,context.getBean(PortalRunEventsSse.class).getClass());assertSame(PortalRunEventsHttp.class,context.getBean(PortalRunEventsHttp.class).getClass());assertSame(RunEventsProxy.class,context.getBean(RunEventsProxy.class).getClass());
157:                 var controlled=assertInstanceOf(PortalRunEventsSse.ControlledProtocol.class,((TomcatWebServer)context.getWebServer()).getTomcat().getConnector().getProtocolHandler());
158:                 assertEquals(1024,controlled.getMaxConnections());assertEquals(64,controlled.getMaxThreads());assertEquals(64,controlled.getProcessorCache());assertInstanceOf(org.apache.tomcat.util.threads.ThreadPoolExecutor.class,controlled.getExecutor());assertEquals(0,controlled.findUpgradeProtocols().length);
159:                 var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+"/admin/runs/"+f.run+"/events"+(mode.equals("query")?"?Last-Event-ID=0":""))).header("Authorization","Bearer "+f.source).timeout(Duration.ofSeconds(15));
160:                 if(mode.equals("header"))request.header("Last-Event-ID","0");
161:                 if(mode.startsWith("postasync")){
162:                     // JDK HttpClient retries a GET on pre-header CLOSE_NOW. One physical request
163:                     // isolates the owned response and cannot mistake a second 503 for a JSON tail.
164:                     try(var socket=new Socket("127.0.0.1",context.getWebServer().getPort())){
165:                         socket.setSoTimeout(12000);
166:                         socket.getOutputStream().write(utf("GET /admin/runs/"+f.run+"/events HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nAuthorization: Bearer "+f.source+"\r\n\r\n"));socket.getOutputStream().flush();
167:                         try{assertFalse(new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8).contains("\"code\":"));}catch(SocketException expected){}
168:                     }
169:                 }else try{
170:                     var response=http.send(request.GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
171:                     if(mode.equals("q403")){assertEquals(403,response.statusCode());assertEquals("RUN_AUTHORITY_DENIED",Json.read(response.body()).path("code").asText());}
172:                     else if(mode.equals("qtransport")){assertEquals(503,response.statusCode());assertEquals("RUN_EVENTS_UNAVAILABLE",Json.read(response.body()).path("code").asText());}
173:                     else if(!mode.startsWith("postasync")){
174:                         assertEquals(200,response.statusCode());assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));assertTrue(response.body().contains("id: 2\n"));assertTrue(response.body().contains("event: stream_status"));assertTrue(response.body().contains("data: null"));assertEquals(0,writers.get());
175:                     }
176:                 }catch(IOException failure){throw failure;}
177:                 long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(PortalEventsPacketBudget.SHARED.owners()!=0&&System.nanoTime()<deadline)Thread.sleep(10);
178:                 assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(3145728,PortalEventsPacketBudget.SHARED.reservedBytes());
179:                 if(mode.startsWith("postasync")){assertEquals(1,requests.get());assertEquals(0,writers.get());assertTrue(asyncGetters.get()>0);if(mode.startsWith("postasync-cleanup"))assertTrue(completionThrows.get()>0);}
180:                 boolean preasyncFailure=mode.equals("q403")||mode.equals("qtransport");
181:                 if(!preasyncFailure)assertTrue(asyncGetters.get()>0,"actual supplied MVC response getter reached");assertEquals(preasyncFailure?1:2,peer.connections);
182:                 Files.writeString(run.resolve("boot.properties"),"actualController=true\nactualSecurityWrappers=true\nproductionBeans=true\nactualProtocol="+controlled.getClass().getName()+"\nmaxConnections=1024\nmaxThreads=64\nprocessorCache=64\ninternalExecutor=true\nhttp2=false\ngetters="+getters+"\nwriters="+writers+"\nasyncGetters="+asyncGetters+"\ncleanupThrows="+completionThrows+"\nfinalOwners=0\nfinalBytes=3145728\nprivateConnections="+peer.connections+"\n");
183:             }
184:         }
185:         assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().equals("portal-events-sse-reader")));
186:         Files.writeString(run.resolve("peer-exit.properties"),"contextClosed=true\npeerClosed=true\nnoLiveSseReader=true\n");
187:     }
188:     static void noProxy(Path run)throws Exception{
189:         try(var f=new LoopbackAuthority()){
190:             iam=f;try(var context=start(run,false);var http=HttpClient.newHttpClient()){
191:                 var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+"/admin/runs/r_"+"a".repeat(32)+"/events")).header("Authorization","Bearer "+f.portal()).GET().build(),HttpResponse.BodyHandlers.ofString());
192:                 assertEquals(403,response.statusCode());assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertTrue(context.getBeansOfType(RunEventsProxy.class).isEmpty());
193:             }
194:         }Files.writeString(run.resolve("no-proxy.properties"),"actualControllerDenied=403\nnewSession=0\n");
195:     }
196:     static void badProfile(Path run)throws Exception{
197:         try(var f=new LoopbackAuthority();var tls=new PortalEventsTls(URI.create("https://"+HOST+":443"),new byte[]{127,0,0,1},PortalRunEventsHttpTest.ca(run,"root"));var authority=f.client("gateway")){
198:             iam=f;endpoint=tls;client=authority;
199:             Throwable failure=assertThrows(RuntimeException.class,()->{try(var ignored=start(run,true)){fail("invalid profile started");}});
200:             boolean found=false;for(Throwable cursor=failure;cursor!=null;cursor=cursor.getCause())if(cursor.getMessage()!=null&&cursor.getMessage().contains(mode.equals("bad-custom-executor")?"Custom executor prohibited":"Controlled HTTP/1.1 NIO profile changed"))found=true;
201:             assertTrue(found,"actual controlled profile must reject the final Boot override");assertEquals(0,PortalEventsPacketBudget.SHARED.owners());
202:             Files.writeString(run.resolve("profile-denied.properties"),"mode="+mode+"\nrealBootStartupRejected=true\nnewSession=0\n");
203:         }
204:     }
205:     interface Check {void run(GatewayIngressBoundary boundary,HttpServletRequest request,HttpServletResponse response)throws Exception;}
206:     static MockHttpServletRequest request(String token){var r=new MockHttpServletRequest("GET","/admin/runs/r_"+"a".repeat(32)+"/events");r.addHeader("Authorization","Bearer "+token);return r;}
207:     static int ingress(GatewayIngressBoundary boundary,GatewayController controller,MockHttpServletRequest request,Check check)throws Exception{
208:         var response=new MockHttpServletResponse();boundary.filter().doFilter(request,response,(r,s)->{
209:             var actual=new HttpServletRequestWrapper((HttpServletRequest)r);var supplied=new HttpServletResponseWrapper((HttpServletResponse)s);
210:             try{assertTrue(boundary.preHandle(actual,supplied,new HandlerMethod(controller,GatewayController.class.getMethod("proxy",HttpServletRequest.class,HttpServletResponse.class))));check.run(boundary,actual,supplied);}
211:             catch(RuntimeException|Error failure){throw failure;}catch(Exception failure){throw new ServletException(failure);}
212:             finally{boundary.afterCompletion(actual,supplied,null,null);}
213:         });return response.getStatus();
214:     }
215:     static void denied(Runnable action){assertThrows(PlatformException.class,action::run);}
216:     static void frameworkBoundary(Path run)throws Exception{
217:         try(var f=new LoopbackAuthority();var decoder=new TrustedJwtDecoder(f.config("gateway"),f.clock(),true)){
218:             var controller=new GatewayController(new StandardEnvironment(),new IntegrationAccess(f.issuer(),f.issuer(),decoder),f.config("gateway"),Optional.empty());
219:             var boundary=new GatewayIngressBoundary(controller,null,false,true,decoder,f.clock());String token=f.portal(120);
220:             var saved=new AtomicReference<Object>();
221:             assertEquals(200,ingress(boundary,controller,request(token),(b,r,s)->{
222:                 saved.set(r.getAttribute(GatewayIngressBoundary.class.getName()+".proof"));
223:                 var manager=WebAsyncUtils.getAsyncManager(r);var current=new StandardServletAsyncWebRequest(r,s);manager.setAsyncWebRequest(current);
224:                 var actual=current.getResponse();assertNotSame(s,actual);
225:                 denied(()->GatewayIngressBoundary.claimEvents(r,s));denied(()->GatewayIngressBoundary.claimEvents(r,new HttpServletResponseWrapper(actual)));
226:                 var otherManager=WebAsyncUtils.getAsyncManager(new MockHttpServletRequest());r.setAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE,otherManager);denied(()->GatewayIngressBoundary.claimEvents(r,actual));r.setAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE,manager);
227:                 manager.setAsyncWebRequest(new StandardServletAsyncWebRequest(new HttpServletRequestWrapper(r),s));denied(()->GatewayIngressBoundary.claimEvents(r,manager.getAsyncWebRequest().getNativeResponse(HttpServletResponse.class)));
228:                 manager.setAsyncWebRequest(new StandardServletAsyncWebRequest(r,new MockHttpServletResponse()));denied(()->GatewayIngressBoundary.claimEvents(r,manager.getAsyncWebRequest().getNativeResponse(HttpServletResponse.class)));
229:                 manager.setAsyncWebRequest(new StandardServletAsyncWebRequest(r,s){});denied(()->GatewayIngressBoundary.claimEvents(r,manager.getAsyncWebRequest().getNativeResponse(HttpServletResponse.class)));
230:                 manager.setAsyncWebRequest(new StandardServletAsyncWebRequest(r,s));denied(()->GatewayIngressBoundary.claimEvents(r,actual));
231:                 manager.setAsyncWebRequest(current);var capture=GatewayIngressBoundary.claimEvents(r,actual);capture.requireLive(r,actual);denied(()->capture.requireLive(r,new HttpServletResponseWrapper(actual)));denied(()->capture.requireLive(r,s));
232:                 manager.setAsyncWebRequest(new StandardServletAsyncWebRequest(r,s));denied(()->capture.requireLive(r,actual));manager.setAsyncWebRequest(current);
233:                 r.setAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE,otherManager);denied(()->capture.requireLive(r,actual));r.setAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE,manager);
234:                 b.afterCompletion(r,s,null,null);denied(()->capture.requireLive(r,actual));
235:             }));
236:             for(String field:List.of("eventsRequest","eventsResponse","eventsHandlerResponse","eventsManager","eventsAsyncRequest"))assertNull(PortalRunEventsSseLifecycleTest.field(saved.get(),field));
237:             var r=request(token);var response=new MockHttpServletResponse();
238:             boundary.filter().doFilter(r,response,(a,s)->{
239:                 try{boundary.preHandle((HttpServletRequest)a,(HttpServletResponse)s,new HandlerMethod(new GatewayController(new StandardEnvironment(),null,f.config("gateway"),Optional.empty()),GatewayController.class.getMethod("proxy",HttpServletRequest.class,HttpServletResponse.class)));}
240:                 catch(ReflectiveOperationException failure){throw new ServletException(failure);}
241:             });assertEquals(403,response.getStatus());
242:             assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertTrue(f.observedOperations().isEmpty());
243:             Files.writeString(run.resolve("framework-binding.properties"),"exactManager=true\nexactStandardFrameworkCurrent=true\nexactHandlerResponse=true\ndirectPreHandleDelegate=true\nforeignManagerRejected=true\nforeignNativeResponseRejected=true\narbitraryWrapperRejected=true\nsubclassRejected=true\nforeignHandlerRejected=true\nclearCutsFiveReverseReferences=true\nnewSession=0\nnewQuery=0\n");
244:         }
245:     }
246:     static void boundary(Path run)throws Exception{
247:         try(var f=new LoopbackAuthority();var decoder=new TrustedJwtDecoder(f.config("gateway"),f.clock(),true);var tls=new PortalEventsTls(URI.create("https://"+HOST+":443"),new byte[]{127,0,0,1},PortalRunEventsHttpTest.ca(run,"root"));var authority=f.client("gateway")){
248:             var integration=new IntegrationAccess(f.issuer(),f.issuer(),decoder);var env=new StandardEnvironment();
249:             var controller=new GatewayController(env,integration,f.config("gateway"),Optional.empty(),Optional.empty(),Optional.empty());
250:             assertNotNull(new GatewayController(env,integration,f.config("gateway"),Optional.empty()));assertNotNull(new GatewayController(env,integration,f.config("gateway"),Optional.empty(),Optional.empty()));
251:             var boundary=new GatewayIngressBoundary(controller,null,true,true,decoder,f.clock());String token=f.portal(120);var http=new PortalRunEventsHttp(tls);var sse=new PortalRunEventsSse();
252:             for(boolean query:new boolean[]{false,true})for(String value:List.of("0","1",Long.toString(Long.MAX_VALUE))){
253:                 var r=request(token);if(query)r.setQueryString("Last-Event-ID="+value);else r.addHeader("Last-Event-ID",value);
254:                 assertEquals(200,ingress(boundary,controller,r,(b,actual,supplied)->{
255:                     var capture=GatewayIngressBoundary.claimEvents(actual,supplied);assertEquals(Long.parseLong(value),capture.cursor());assertEquals("r_"+"a".repeat(32),capture.publicRunId());assertEquals(new SubjectKey("tenant-a","alice"),capture.subject());capture.requireLive(actual,supplied);
256:                     r.removeHeader("Last-Event-ID");r.addHeader("Last-Event-ID","invalid-after-capture");capture.requireLive(actual,supplied);assertEquals(Long.parseLong(value),capture.cursor());
257:                 }));
258:             }
259:             var escaped=new AtomicReference<GatewayIngressBoundary.PortalEventsRequest>();var savedR=new AtomicReference<HttpServletRequest>();var savedS=new AtomicReference<HttpServletResponse>();var savedProof=new AtomicReference<Object>();
260:             assertEquals(200,ingress(boundary,controller,request(token),(b,r,s)->{
261:                 var capture=GatewayIngressBoundary.claimEvents(r,s);assertEquals(0,capture.cursor());escaped.set(capture);savedR.set(r);savedS.set(s);savedProof.set(r.getAttribute(GatewayIngressBoundary.class.getName()+".proof"));
262:                 denied(()->GatewayIngressBoundary.claimEvents(r,s));denied(()->GatewayIngressBoundary.claim(r));denied(()->GatewayIngressBoundary.claimStatus(r));denied(()->GatewayIngressBoundary.claimSession(r));
263:                 denied(()->capture.requireLive(new HttpServletRequestWrapper(r),s));denied(()->capture.requireLive(r,new HttpServletResponseWrapper(s)));denied(()->capture.requireLive(new MockHttpServletRequest("GET",r.getRequestURI()),s));denied(()->capture.requireLive(r,new MockHttpServletResponse()));
264:                 var foreign=request(token);foreign.setAttribute(GatewayIngressBoundary.class.getName()+".proof",savedProof.get());denied(()->GatewayIngressBoundary.claimEvents(foreign,s));
265:                 var error=new AtomicReference<Throwable>();var thread=Thread.ofPlatform().start(()->{try{capture.requireLive(r,s);}catch(Throwable failure){error.set(failure);}});thread.join(2000);assertFalse(thread.isAlive());assertInstanceOf(PlatformException.class,error.get());
266:                 assertThrows(PlatformException.class,()->sse.start(new HttpServletRequestWrapper(r),s,http,authority,capture));assertThrows(IllegalStateException.class,()->sse.start(r,s,http,authority,capture),"no controlled original token");
267:                 b.afterConcurrentHandlingStarted(r,s,null);denied(()->capture.requireLive(r,s));
268:             }));
269:             denied(()->escaped.get().requireLive(savedR.get(),savedS.get()));assertNull(PortalRunEventsSseLifecycleTest.field(savedProof.get(),"eventsRequest"));assertNull(PortalRunEventsSseLifecycleTest.field(savedProof.get(),"eventsResponse"));
270:             var invalid=new ArrayList<Consumer<MockHttpServletRequest>>();
271:             for(String value:List.of("","-1","+1","00","01"," 1","1 ","1,2","9223372036854775808","99999999999999999999","１","١")){invalid.add(r->r.addHeader("Last-Event-ID",value));invalid.add(r->r.setQueryString("Last-Event-ID="+value));}
272:             for(String query:List.of("x=1","last-event-id=0","%4cast-Event-ID=0","Last-Event-ID=%30","Last-Event-ID=0&Last-Event-ID=0","Last-Event-ID=0&tenant=x","Last-Event-ID=0;tenant=x","tenant=x","Last-Event-ID=0&"))invalid.add(r->r.setQueryString(query));
273:             invalid.add(r->{r.addHeader("Last-Event-ID","0");r.addHeader("Last-Event-ID","0");});invalid.add(r->{r.addHeader("Last-Event-ID","0");r.setQueryString("Last-Event-ID=0");});
274:             for(String header:List.of("Cookie","X-Api-Key","ApiKey","X-User-id","X-Tenant-id","X-Principal","X-Actor-id","tenant","subject","Proxy-Authorization","X-Auth-token"))invalid.add(r->r.addHeader(header,"forged"));
275:             invalid.add(r->r.setContent(utf("body")));invalid.add(r->r.addHeader("Transfer-Encoding","chunked"));invalid.add(r->r.setContextPath("/ctx"));invalid.add(r->r.setDispatcherType(DispatcherType.ASYNC));invalid.add(r->r.setMethod("POST"));
276:             invalid.add(r->r.setRequestURI("/admin/runs/r_"+"A".repeat(32)+"/events"));invalid.add(r->r.setRequestURI("/admin/runs/r_"+"a".repeat(32)+"/events/"));invalid.add(r->r.setRequestURI("/admin/runs/r_"+"a".repeat(32)+"/%65vents"));
277:             for(var mutate:invalid){var r=request(token);mutate.accept(r);assertEquals(403,ingress(boundary,controller,r,(b,a,s)->fail("invalid ingress reached handler")));}
278:             var noEvents=new GatewayIngressBoundary(controller,null,true,decoder,f.clock());assertEquals(403,ingress(noEvents,controller,request(token),(b,a,s)->fail("missing component")));
279:             for(String path:List.of("/admin/runs/r_"+"a".repeat(32),"/admin/apps/Demo/runs","/auth/session")){
280:                 var r=request(token);r.setRequestURI(path);if(path.contains("apps"))r.setMethod("POST");r.setQueryString("Last-Event-ID=0");assertEquals(403,ingress(boundary,controller,r,(b,a,s)->fail("other purpose query broadened")));
281:             }
282:             assertEquals(200,ingress(boundary,controller,request(token),(b,r,s)->{var capture=GatewayIngressBoundary.claimEvents(r,s);f.advanceClock(Duration.ofSeconds(121));assertEquals(401,assertThrows(PlatformException.class,()->capture.requireLive(r,s)).status());}));
283:             assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes());assertTrue(f.observedOperations().isEmpty(),"no opening/Q before capture and controlled token checks");
284:             Files.writeString(run.resolve("boundary.properties"),"cursorAccepted=default0,header0,header1,headerLongMax,query0,query1,queryLongMax\ninvalidVectors="+invalid.size()+"\nexactPairAndThread=true\nclearCutsReverseReferences=true\nprivateProofOnly=true\nnewSession=0\nnewQuery=0\noldConstructors=true\n");
285:         }
286:     }
287: }