package org.modelix.services.workspaces

import io.kubernetes.client.common.KubernetesObject
import io.kubernetes.client.openapi.ApiCallback
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.AppsV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Deployment
import io.kubernetes.client.openapi.models.V1DeploymentList
import io.kubernetes.client.openapi.models.V1DeploymentSpec
import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1JobSpec
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1PodSpec
import io.kubernetes.client.openapi.models.V1PodTemplateSpec
import io.kubernetes.client.openapi.models.V1ServiceList
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

fun KubernetesObject.metadata(body: V1ObjectMeta.() -> Unit): V1ObjectMeta {
    return (metadata ?: V1ObjectMeta().also { setMetadata(it) }).apply(body)
}
fun V1PodTemplateSpec.metadata(body: V1ObjectMeta.() -> Unit): V1ObjectMeta {
    return (metadata ?: V1ObjectMeta().also { setMetadata(it) }).apply(body)
}

fun KubernetesObject.setMetadata(data: V1ObjectMeta) {
    when (this) {
        is io.kubernetes.client.openapi.models.AuthenticationV1TokenRequest -> metadata = data
        is io.kubernetes.client.openapi.models.CoreV1Event -> metadata = data
        is io.kubernetes.client.util.generic.dynamic.DynamicKubernetesObject -> metadata = data
        is io.kubernetes.client.openapi.models.EventsV1Event -> metadata = data
        is io.kubernetes.client.custom.NodeMetrics -> metadata = data
        is io.kubernetes.client.custom.PodMetrics -> metadata = data
        is io.kubernetes.client.openapi.models.V1alpha1ClusterTrustBundle -> metadata = data
        is io.kubernetes.client.openapi.models.V1alpha1MutatingAdmissionPolicy -> metadata = data
        is io.kubernetes.client.openapi.models.V1alpha1MutatingAdmissionPolicyBinding -> metadata = data
        is io.kubernetes.client.openapi.models.V1alpha1StorageVersion -> metadata = data
        is io.kubernetes.client.openapi.models.V1alpha1StorageVersionMigration -> metadata = data
        is io.kubernetes.client.openapi.models.V1alpha1VolumeAttributesClass -> metadata = data
        is io.kubernetes.client.openapi.models.V1alpha2LeaseCandidate -> metadata = data
        is io.kubernetes.client.openapi.models.V1alpha3DeviceClass -> metadata = data
        is io.kubernetes.client.openapi.models.V1alpha3ResourceClaim -> metadata = data
        is io.kubernetes.client.openapi.models.V1alpha3ResourceClaimTemplate -> metadata = data
        is io.kubernetes.client.openapi.models.V1alpha3ResourceSlice -> metadata = data
        is io.kubernetes.client.openapi.models.V1APIService -> metadata = data
        is io.kubernetes.client.openapi.models.V1beta1DeviceClass -> metadata = data
        is io.kubernetes.client.openapi.models.V1beta1IPAddress -> metadata = data
        is io.kubernetes.client.openapi.models.V1beta1ResourceClaim -> metadata = data
        is io.kubernetes.client.openapi.models.V1beta1ResourceClaimTemplate -> metadata = data
        is io.kubernetes.client.openapi.models.V1beta1ResourceSlice -> metadata = data
        is io.kubernetes.client.openapi.models.V1beta1SelfSubjectReview -> metadata = data
        is io.kubernetes.client.openapi.models.V1beta1ServiceCIDR -> metadata = data
        is io.kubernetes.client.openapi.models.V1beta1ValidatingAdmissionPolicy -> metadata = data
        is io.kubernetes.client.openapi.models.V1beta1ValidatingAdmissionPolicyBinding -> metadata = data
        is io.kubernetes.client.openapi.models.V1beta1VolumeAttributesClass -> metadata = data
        is io.kubernetes.client.openapi.models.V1Binding -> metadata = data
        is io.kubernetes.client.openapi.models.V1CertificateSigningRequest -> metadata = data
        is io.kubernetes.client.openapi.models.V1ClusterRole -> metadata = data
        is io.kubernetes.client.openapi.models.V1ClusterRoleBinding -> metadata = data
        is io.kubernetes.client.openapi.models.V1ComponentStatus -> metadata = data
        is io.kubernetes.client.openapi.models.V1ConfigMap -> metadata = data
        is io.kubernetes.client.openapi.models.V1ControllerRevision -> metadata = data
        is io.kubernetes.client.openapi.models.V1CronJob -> metadata = data
        is io.kubernetes.client.openapi.models.V1CSIDriver -> metadata = data
        is io.kubernetes.client.openapi.models.V1CSINode -> metadata = data
        is io.kubernetes.client.openapi.models.V1CSIStorageCapacity -> metadata = data
        is io.kubernetes.client.openapi.models.V1CustomResourceDefinition -> metadata = data
        is io.kubernetes.client.openapi.models.V1DaemonSet -> metadata = data
        is io.kubernetes.client.openapi.models.V1Deployment -> metadata = data
        is io.kubernetes.client.openapi.models.V1Endpoints -> metadata = data
        is io.kubernetes.client.openapi.models.V1EndpointSlice -> metadata = data
        is io.kubernetes.client.openapi.models.V1Eviction -> metadata = data
        is io.kubernetes.client.openapi.models.V1FlowSchema -> metadata = data
        is io.kubernetes.client.openapi.models.V1HorizontalPodAutoscaler -> metadata = data
        is io.kubernetes.client.openapi.models.V1Ingress -> metadata = data
        is io.kubernetes.client.openapi.models.V1IngressClass -> metadata = data
        is io.kubernetes.client.openapi.models.V1Job -> metadata = data
        is io.kubernetes.client.openapi.models.V1Lease -> metadata = data
        is io.kubernetes.client.openapi.models.V1LimitRange -> metadata = data
        is io.kubernetes.client.openapi.models.V1LocalSubjectAccessReview -> metadata = data
        is io.kubernetes.client.openapi.models.V1MutatingWebhookConfiguration -> metadata = data
        is io.kubernetes.client.openapi.models.V1Namespace -> metadata = data
        is io.kubernetes.client.openapi.models.V1NetworkPolicy -> metadata = data
        is io.kubernetes.client.openapi.models.V1Node -> metadata = data
        is io.kubernetes.client.openapi.models.V1PersistentVolume -> metadata = data
        is io.kubernetes.client.openapi.models.V1PersistentVolumeClaim -> metadata = data
        is io.kubernetes.client.openapi.models.V1Pod -> metadata = data
        is io.kubernetes.client.openapi.models.V1PodDisruptionBudget -> metadata = data
        is io.kubernetes.client.openapi.models.V1PodTemplate -> metadata = data
        is io.kubernetes.client.openapi.models.V1PriorityClass -> metadata = data
        is io.kubernetes.client.openapi.models.V1PriorityLevelConfiguration -> metadata = data
        is io.kubernetes.client.openapi.models.V1ReplicaSet -> metadata = data
        is io.kubernetes.client.openapi.models.V1ReplicationController -> metadata = data
        is io.kubernetes.client.openapi.models.V1ResourceQuota -> metadata = data
        is io.kubernetes.client.openapi.models.V1Role -> metadata = data
        is io.kubernetes.client.openapi.models.V1RoleBinding -> metadata = data
        is io.kubernetes.client.openapi.models.V1RuntimeClass -> metadata = data
        is io.kubernetes.client.openapi.models.V1Scale -> metadata = data
        is io.kubernetes.client.openapi.models.V1Secret -> metadata = data
        is io.kubernetes.client.openapi.models.V1SelfSubjectAccessReview -> metadata = data
        is io.kubernetes.client.openapi.models.V1SelfSubjectReview -> metadata = data
        is io.kubernetes.client.openapi.models.V1SelfSubjectRulesReview -> metadata = data
        is io.kubernetes.client.openapi.models.V1Service -> metadata = data
        is io.kubernetes.client.openapi.models.V1ServiceAccount -> metadata = data
        is io.kubernetes.client.openapi.models.V1StatefulSet -> metadata = data
        is io.kubernetes.client.openapi.models.V1StorageClass -> metadata = data
        is io.kubernetes.client.openapi.models.V1SubjectAccessReview -> metadata = data
        is io.kubernetes.client.openapi.models.V1TokenReview -> metadata = data
        is io.kubernetes.client.openapi.models.V1ValidatingAdmissionPolicy -> metadata = data
        is io.kubernetes.client.openapi.models.V1ValidatingAdmissionPolicyBinding -> metadata = data
        is io.kubernetes.client.openapi.models.V1ValidatingWebhookConfiguration -> metadata = data
        is io.kubernetes.client.openapi.models.V1VolumeAttachment -> metadata = data
        is io.kubernetes.client.openapi.models.V2HorizontalPodAutoscaler -> metadata = data
        else -> throw UnsupportedOperationException("Unknown object type: $this")
    }
}

fun V1Deployment.spec(body: V1DeploymentSpec.() -> Unit): V1DeploymentSpec {
    return (spec ?: V1DeploymentSpec().also { spec = it }).apply(body)
}

fun V1Job.spec(body: V1JobSpec.() -> Unit): V1JobSpec {
    return (spec ?: V1JobSpec().also { spec = it }).apply(body)
}

fun V1JobSpec.template(body: V1PodTemplateSpec.() -> Unit): V1PodTemplateSpec {
    return (template ?: V1PodTemplateSpec().also { template = it }).apply(body)
}

fun V1PodTemplateSpec.spec(body: V1PodSpec.() -> Unit): V1PodSpec {
    return (spec ?: V1PodSpec().also { spec = it }).apply(body)
}

class ContinuingCallback<T>(val continuation: Continuation<T>) : ApiCallback<T> {
    override fun onDownloadProgress(p0: Long, p1: Long, p2: Boolean) {}
    override fun onUploadProgress(p0: Long, p1: Long, p2: Boolean) {}

    override fun onFailure(
        ex: ApiException,
        p1: Int,
        p2: Map<String?, List<String?>?>?,
    ) {
        continuation.resumeWith(Result.failure<T>(ex))
    }

    override fun onSuccess(
        returnedValue: T,
        p1: Int,
        p2: Map<String?, List<String?>?>?,
    ) {
        continuation.resumeWith(Result.success(returnedValue))
    }
}

suspend fun AppsV1Api.APIcreateNamespacedDeploymentRequest.executeSuspending(): V1Deployment =
    suspendCoroutine { executeAsync(ContinuingCallback(it)) }
suspend fun CoreV1Api.APIlistNamespacedServiceRequest.executeSuspending(): V1ServiceList =
    suspendCoroutine { executeAsync(ContinuingCallback(it)) }
suspend fun AppsV1Api.APIlistNamespacedDeploymentRequest.executeSuspending(): V1DeploymentList =
    suspendCoroutine { executeAsync(ContinuingCallback(it)) }
