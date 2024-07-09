require('dotenv').config();
const express = require('express');
const bodyParser = require('body-parser');
const morgan = require('morgan');
const fs = require('fs');
const path = require('path');
const winston = require('winston');
const moment = require('moment-timezone');
const crypto = require('crypto');
require('winston-daily-rotate-file');

const app = express();
const port = process.env.PORT || 4000;

// ログファイルのパスを設定
const logDirectory = path.join('/var/log/zoom/logs');
fs.existsSync(logDirectory) || fs.mkdirSync(logDirectory);

// 日本時間のタイムスタンプを取得するフォーマットを定義
const jstTimestamp = () => moment().tz('Asia/Tokyo').format('YYYY-MM-DD HH:mm:ss');

// 日付ごとにログファイルを分割する設定
const appDailyRotateFileTransport = new winston.transports.DailyRotateFile({
  dirname: logDirectory,
  filename: 'server.log.%DATE%',
  datePattern: 'YYYY-MM-DD',
  zippedArchive: true,
  maxSize: '20m',
  maxFiles: '14d',
  createSymlink: true,
  symlinkName: 'server.log'
});

const errorDailyRotateFileTransport = new winston.transports.DailyRotateFile({
  dirname: logDirectory,
  filename: 'error.log.%DATE%',
  datePattern: 'YYYY-MM-DD',
  zippedArchive: true,
  maxSize: '20m',
  maxFiles: '14d',
  createSymlink: true,
  symlinkName: 'error.log'
});

const accessDailyRotateFileTransport = new winston.transports.DailyRotateFile({
  dirname: logDirectory,
  filename: 'access.log.%DATE%',
  datePattern: 'YYYY-MM-DD',
  zippedArchive: true,
  maxSize: '20m',
  maxFiles: '14d',
  createSymlink: true,
  symlinkName: 'access.log'
});

// winstonを設定
const appLogger = winston.createLogger({
  level: 'info',
  format: winston.format.combine(
    winston.format.timestamp({ format: jstTimestamp }),
    winston.format.printf(({ timestamp, level, message }) => {
      if (typeof message === 'object') {
        message = JSON.stringify(message, null, 2);
      }
      return `${timestamp} ${level}: ${message}`;
    })
  ),
  transports: [
    appDailyRotateFileTransport,
    new winston.transports.Console()
  ]
});

const errorLogger = winston.createLogger({
  level: 'error',
  format: winston.format.combine(
    winston.format.timestamp({ format: jstTimestamp }),
    winston.format.printf(({ timestamp, level, message }) => {
      if (typeof message === 'object') {
        message = JSON.stringify(message, null, 2);
      }
      return `${timestamp} ${level}: ${message}`;
    })
  ),
  transports: [
    errorDailyRotateFileTransport,
    new winston.transports.Console()
  ]
});

// morganを設定してアクセスログを別ファイルに記録
app.use(morgan('combined', { stream: accessDailyRotateFileTransport.stream }));

app.use(bodyParser.json());

app.get('/', (req, res) => {
  res.status(200).send(`Zoom Webhook sample successfully running. Set this URL with the /webhook path as your apps Event notification endpoint URL. https://github.com/zoom/webhook-sample`);
});

app.post('/webhook', (req, res) => {
  let response;

  appLogger.info(req.headers);
  appLogger.info(req.body);

  // construct the message string
  const message = `v0:${req.headers['x-zm-request-timestamp']}:${JSON.stringify(req.body)}`;

  const hashForVerify = crypto.createHmac('sha256', process.env.ZOOM_WEBHOOK_SECRET_TOKEN).update(message).digest('hex');

  // hash the message string with your Webhook Secret Token and prepend the version semantic
  const signature = `v0=${hashForVerify}`;

  // you validating the request came from Zoom https://marketplace.zoom.us/docs/api-reference/webhook-reference#notification-structure
  if (req.headers['x-zm-signature'] === signature) {
    // Zoom validating you control the webhook endpoint https://marketplace.zoom.us/docs/api-reference/webhook-reference#validate-webhook-endpoint
    if (req.body.event === 'endpoint.url_validation') {
      const hashForValidate = crypto.createHmac('sha256', process.env.ZOOM_WEBHOOK_SECRET_TOKEN).update(req.body.payload.plainToken).digest('hex');

      response = {
        message: {
          plainToken: req.body.payload.plainToken,
          encryptedToken: hashForValidate
        },
        status: 200
      };

      appLogger.info(response.message);

      res.status(response.status).json(response.message);
    } else {
      response = { message: 'Authorized request to Zoom Webhook sample.', status: 200 };

      appLogger.info(response.message);

      res.status(response.status).json(response);

      // business logic here, example make API request to Zoom or 3rd party
    }
  } else {
    response = { message: 'Unauthorized request to Zoom Webhook sample.', status: 401 };

    appLogger.info(response.message);

    res.status(response.status).json(response);
  }
});

app.listen(port, () => appLogger.info(`Zoom Webhook sample listening on port ${port}!`));
