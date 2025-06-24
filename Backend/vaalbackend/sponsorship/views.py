from rest_framework.decorators import api_view
from rest_framework.response import Response
from rest_framework import status
from .models import ConnectionSession
from django.conf import settings
from django.utils import timezone

@api_view(['POST'])
def create_session(request):
    # Clean expired sessions first
    ConnectionSession.clean_expired()
    
    host_device_id = request.data.get('device_id', '')
    if not host_device_id:
        return Response({"error": "Device ID is required"}, status=status.HTTP_400_BAD_REQUEST)
    
    session = ConnectionSession.objects.create(host_device_id=host_device_id)
    return Response({
        "code": session.session_id,
        "relay_server": settings.RELAY_SERVER,
        "udp_port": settings.RELAY_UDP_PORT,
        "tcp_port": settings.RELAY_TCP_PORT
    }, status=status.HTTP_201_CREATED)

@api_view(['POST'])
def join_session(request):
    code = request.data.get('code', '').upper()
    client_device_id = request.data.get('device_id', '')
    
    if not code or not client_device_id:
        return Response({"error": "Code and device ID are required"}, status=status.HTTP_400_BAD_REQUEST)
    
    try:
        session = ConnectionSession.objects.get(session_id=code)
        if not session.is_active or session.expires_at < timezone.now():
            return Response({"error": "Session expired or inactive"}, status=status.HTTP_410_GONE)
            
        session.client_device_id = client_device_id
        session.save()
        return Response({
            "status": "success",
            "relay_server": settings.RELAY_SERVER,
            "udp_port": settings.RELAY_UDP_PORT,
            "tcp_port": settings.RELAY_TCP_PORT
        })
    except ConnectionSession.DoesNotExist:
        return Response({"error": "Invalid session code"}, status=status.HTTP_404_NOT_FOUND)

@api_view(['POST'])
def cancel_session(request):
    code = request.data.get('code', '').upper()
    if not code:
        return Response({"error": "Session code is required"}, status=status.HTTP_400_BAD_REQUEST)
    
    try:
        session = ConnectionSession.objects.get(session_id=code)
        session.is_active = False
        session.save()
        return Response({"status": "success"})
    except ConnectionSession.DoesNotExist:
        ConnectionSession.objects.create(
            session_id=code,
            is_active=False,
            host_device_id="system-cancel"
        )
        return Response({"status": "session_marked_inactive"})
        
@api_view(['GET'])
def validate_session(request, session_code):
    try:
        session = ConnectionSession.objects.get(session_id=session_code)
        return Response({
            "active": session.is_active and session.expires_at > timezone.now()
        })
    except ConnectionSession.DoesNotExist:
        return Response({"active": False}, status=status.HTTP_404_NOT_FOUND)

@api_view(['GET'])
def health_check(request):
    return Response({"status": "ok", "time": timezone.now().isoformat()})