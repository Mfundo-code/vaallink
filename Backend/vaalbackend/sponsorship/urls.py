from django.urls import path
from .views import create_session, join_session, cancel_session, validate_session, health_check

urlpatterns = [
    path('create/', create_session, name='create-session'),
    path('join/', join_session, name='join-session'),
    path('cancel/', cancel_session, name='cancel-session'),
    path('validate/<str:session_code>/', validate_session, name='validate-session'),
    path('health/', health_check, name='health-check'),
]